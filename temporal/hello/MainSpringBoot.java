///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//FILES application.properties=application-spring-boot.properties
//DEPS org.springframework.boot:spring-boot-dependencies:3.4.1@pom
//DEPS org.springframework.boot:spring-boot-starter-web
//DEPS org.springframework.boot:spring-boot-starter-actuator
//DEPS org.springframework.boot:spring-boot-starter-validation
//DEPS io.temporal:temporal-spring-boot-starter:1.27.0
//DEPS io.micrometer:micrometer-registry-prometheus


package temporal.hello;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributeUpdate;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.serviceclient.SimpleSslContextBuilder;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.spring.boot.ActivityImpl;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.WorkerOptionsCustomizer;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.*;

@SpringBootApplication
public class MainSpringBoot {

    public static void main(String[] args) {
        SpringApplication.run(MainSpringBoot.class, args);
    }

    @Bean
    @Profile("cloud")
    public TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder>
    customServiceStubsOptions(
            @Value("${app.temporal.apiKey}") String apiKey,
            @Value("${spring.temporal.namespace}") String namespace
    ) {

        return optionsBuilder -> {
            var key = Metadata.Key.of("temporal-namespace", Metadata.ASCII_STRING_MARSHALLER);
            var metadata = new Metadata();
            metadata.put(key, namespace);

            optionsBuilder.setChannelInitializer(
                    (channel) -> channel.intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))
            );

            try {
                optionsBuilder.setSslContext(
                        SimpleSslContextBuilder.noKeyOrCertChain().setUseInsecureTrustManager(false).build());
            } catch (SSLException e) {
                throw new RuntimeException(e);
            }

            optionsBuilder.addApiKey(() -> apiKey);
            optionsBuilder.setEnableHttps(true);

            return optionsBuilder;
        };
    }

    @Bean
    public WorkerOptionsCustomizer customWorkerOptions() {
        return (optionsBuilder, workerName, taskQueue) -> {

            switch (taskQueue) {
                case "hello-workflow-task-queue" -> optionsBuilder.setIdentity("hello-workflow-worker");
                case "hello-translation-task-queue" -> optionsBuilder.setIdentity("hello-translation-worker");
            }

            return optionsBuilder;
        };
    }

    @RestController
    @RequestMapping("hello")
    public static class HelloController {

        private WorkflowClient client;

        public HelloController(WorkflowClient client) {
            this.client = client;
        }

        @GetMapping("{name}")
        public ResponseEntity sayHello(
                @PathVariable(name = "name") @NotBlank String name,
                @RequestParam(name = "langageCode", required = false) LangageCode langageCode) {

            var workflowId = "hello-%s-%s".formatted(
                    name.toLowerCase(Locale.ROOT),
                    UUID.randomUUID().toString());
            var workflow = this.client.newWorkflowStub(HelloWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(workflowId)
                            .setTaskQueue("hello-workflow-task-queue").build());

            WorkflowClient.start(workflow::sayHello, HelloRequest.of(name, langageCode));

            return ResponseEntity.accepted()
                    .body(Map.of("workflowId", workflowId));
        }

        @GetMapping("workflows/{workflowId}/langageCode/{langageCode}")
        public ResponseEntity giveLangageCode(
                @PathVariable(name = "workflowId") @NotBlank String workflowId,
                @PathVariable(name = "langageCode") @NotNull LangageCode langageCode) {

            this.client.newWorkflowStub(HelloWorkflow.class, workflowId)
                    .langageCode(langageCode);

            return ResponseEntity.accepted()
                    .body(Map.of("workflowId", workflowId, "langageCode", langageCode));
        }

        @GetMapping("workflows/{workflowId}/status")
        public ResponseEntity status(@PathVariable(name = "workflowId") @NotBlank String workflowId) {

            var status = this.client.listExecutions("WorkflowId='%s'".formatted(workflowId))
                    .map(WorkflowExecutionMetadata::getTypedSearchAttributes)
                    .filter(sa -> sa.containsKey(CorporateSearchAttributes.HELLO_WORKFLOW_STATUS))
                    .map(sa -> sa.get(CorporateSearchAttributes.HELLO_WORKFLOW_STATUS))
                    .map(HelloWorkflowStatus::valueOf)
                    .findFirst()
                    .orElse(HelloWorkflowStatus.UNKNOWN);

            return ResponseEntity.ok(Map.of("workflowId", workflowId, "status", status));
        }
    }

    @WorkflowImpl(taskQueues = "hello-workflow-task-queue")
    public static class HelloWorkflowImpl implements HelloWorkflow {

        private LangageCode langageCode;

        ActivityOptions options = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setMaximumInterval(Duration.ofSeconds(100))
                        .setBackoffCoefficient(2)
                        .setMaximumAttempts(500)
                        .build())
                .build();

        HelloTranslationActivity helloTranslationActivity = Workflow.newActivityStub(
                HelloTranslationActivity.class,
                ActivityOptions.newBuilder(options)
                        .setTaskQueue("hello-translation-task-queue")
                        .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                        .build()
        );

        static final Logger LOGGER =  Workflow.getLogger("HelloWorkflow");

        public HelloWorkflowImpl() {
            CorporateSearchAttributes.setStatus(HelloWorkflowStatus.INITIAL);
        }

        public HelloResponse sayHello(HelloRequest helloRequest) {

            LOGGER.info("Say Hello To {}", helloRequest.name());

            this.langageCode = helloRequest.languageCode();

            if (Objects.isNull(this.langageCode)) {
                LOGGER.info("Waiting Signal langageCode ...");
                CorporateSearchAttributes.setStatus(HelloWorkflowStatus.WAITING);
                Workflow.await(() -> !Objects.isNull(this.langageCode));
            }

            String translateResult;
            try {
                translateResult = this.helloTranslationActivity.translateHello(this.langageCode);
            }
            // For Non Retryable Application Failure
            catch (ActivityFailure activityFailure) {
                LOGGER.error(activityFailure.getMessage(), activityFailure);
                CorporateSearchAttributes.setStatus(HelloWorkflowStatus.ERROR);
                throw activityFailure;
            }

            var helloResponse =  HelloResponse.of("%s %s !".formatted(translateResult, helloRequest.name()));

            CorporateSearchAttributes.setStatus(HelloWorkflowStatus.COMPLETED);

            return helloResponse;
        }

        public void langageCode(LangageCode langageCode) {
            LOGGER.info("Receive Signal langageCode : {}", langageCode);
            this.langageCode = langageCode;
        }

    }


    @WorkflowInterface
    public interface HelloWorkflow {

        @WorkflowMethod
        HelloResponse sayHello(HelloRequest helloRequest);

        @SignalMethod
        void langageCode(LangageCode langageCode);

    }

    @Component
    @ActivityImpl(taskQueues = "hello-translation-task-queue")
    public static class HelloTranslationActivityImpl implements HelloTranslationActivity {

        private static final Logger LOGGER = LoggerFactory.getLogger(HelloTranslationActivityImpl.class);

        public String translateHello(LangageCode languageCode) {

            LOGGER.info("Translate Hello To {}", languageCode);

            injectErrorRandomly();

            return switch (languageCode) {
                case fr -> "Bonjour";
                case es -> "Hola";
                case en -> "Hello";
                case wtf -> throw ApplicationFailure.newNonRetryableFailure("invalid langage code: wtf","InvalidLangageCode");
            };
        }

        void injectErrorRandomly() {
            if (new Random().nextBoolean()) {
                var re = new RuntimeException("Translate Service temporarily unavailable");
                throw ApplicationFailure.newFailureWithCause(re.getMessage(), "TranslateServiceUnavailable", re);
            }
        }
    }


    @ActivityInterface
    public interface HelloTranslationActivity {

        @ActivityMethod
        String translateHello(LangageCode languageCode);

    }

    public record HelloRequest(String name, LangageCode languageCode) {
        public static HelloRequest of(String name, LangageCode languageCode) {
            return new HelloRequest(name, languageCode);
        }
    }

    public record HelloResponse(String message) {
        public static HelloResponse of(String message) {
            return new HelloResponse(message);
        }
    }

    public enum LangageCode {
        fr, es, en, wtf
    }

    public enum HelloWorkflowStatus {
        INITIAL, WAITING, UNKNOWN, COMPLETED, ERROR
    }

    public static class CorporateSearchAttributes {

        public static final SearchAttributeKey<String> HELLO_WORKFLOW_STATUS = SearchAttributeKey.forKeyword("OrgCustomStatus");

        private CorporateSearchAttributes() {}

        public static void setStatus(HelloWorkflowStatus status) {
            var update = SearchAttributeUpdate.valueSet(HELLO_WORKFLOW_STATUS, status.name());
            Workflow.upsertTypedSearchAttributes(update);
        }
    }

}