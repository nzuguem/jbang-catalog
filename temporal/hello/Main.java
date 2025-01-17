///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//FILES application.properties
//DEPS io.quarkus:quarkus-bom:3.15.1@pom
//DEPS io.quarkus:quarkus-rest-jackson
//DEPS io.quarkus:quarkus-hibernate-validator
//DEPS io.quarkiverse.temporal:quarkus-temporal:0.0.14

import io.quarkiverse.temporal.TemporalActivity;
import io.quarkiverse.temporal.TemporalWorkflow;
import io.quarkus.logging.Log;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.*;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Path("/hello")
public class Main {

    @Inject
    WorkflowClient client;


    @GET
    @Path("/{name}")
    public Response sayHello(@RestPath @NotBlank String name, @RestQuery LangageCode langageCode) {

        var workflowId = "hello-%s-%s".formatted(name.toLowerCase(Locale.ROOT), UUID.randomUUID().toString());
        var workflow = this.client.newWorkflowStub(HelloWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue("hello-workflow-task-queue").build());

        WorkflowClient.start(workflow::sayHello, HelloRequest.of(name, langageCode));

        return Response.accepted(Map.of("workflowId", workflowId)).build();
    }

    @GET
    @Path("/workflows/{workflowId}/langageCode/{langageCode}")
    public Response giveLangageCode(@RestPath @NotBlank String workflowId, @RestPath @NotNull LangageCode langageCode) {

        this.client.newWorkflowStub(HelloWorkflow.class, workflowId)
                .langageCode(langageCode);

        return Response.accepted(Map.of("workflowId", workflowId, "langageCode", langageCode)).build();
    }

    @GET
    @Path("/workflows/{workflowId}/status")
    public Response status(@RestPath @NotBlank String workflowId) {

        var status = this.client.newWorkflowStub(HelloWorkflow.class, workflowId)
                .getStatus();

        return Response.ok(Map.of("workflowId", workflowId, "status", status)).build();
    }

    @TemporalWorkflow(workers = "hello-workflow-worker")
    public static class HelloWorkflowImpl implements HelloWorkflow {

        private LangageCode langageCode;

        private String status = "INITIAL";

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

        public HelloResponse sayHello(HelloRequest helloRequest) {

            LOGGER.info("Say Hello To {}", helloRequest.name());

            this.langageCode = helloRequest.languageCode();

            Workflow.await(() -> {

                this.status = "WAITING";

                return !Objects.isNull(this.langageCode);
            });

            var hello = this.helloTranslationActivity.translateHello(this.langageCode);

            var helloResponse =  HelloResponse.of("%s %s !".formatted(hello, helloRequest.name()));

            this.status = "COMPLETED";

            return helloResponse;
        }

        public void langageCode(LangageCode langageCode) {
            this.langageCode = langageCode;
        }

        public String getStatus() {
            return this.status;
        }
    }


    @WorkflowInterface
    public interface HelloWorkflow {

        @WorkflowMethod
        HelloResponse sayHello(HelloRequest helloRequest);

        @SignalMethod
        void langageCode(LangageCode langageCode);

        @QueryMethod
        String getStatus();
    }

    @TemporalActivity(workers = "hello-translation-worker")
    public static class HelloTranslationActivityImpl implements HelloTranslationActivity {

        public String translateHello(LangageCode languageCode) {

            Log.infof("Translate Hello To %s", languageCode);

            return switch (languageCode) {
                case fr -> "Bonjour";
                case es -> "Hola";
                case en -> "Hello";
            };
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
        fr, es, en
    }
}