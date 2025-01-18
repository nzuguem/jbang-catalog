# Temporal Hello World

## Play HelloWorkflow 🕹️

### Local Temporal Server

- Start and configure Temporal Server

```bash
## Start Temporal Server
temporal server start-dev

## Create Custom SearchAttributes
temporal operator search-attribute create --namespace default --name OrgCustomStatus --type Keyword
```

- Start **Application Client** & **Workers**
```bash
jbang --fresh temporal-say-hello-workflow@nzuguem
```

### Temporal Cloud ☁️

- Start **Application Client** & **Workers**

```bash
jbang --fresh \
  -Dtarget=<target> \
  -Dnamespace=<ns> \
  -Dquarkus.profile=cloud \
  -Dapi_key=<api_key> \
  temporal-say-hello-workflow@nzuguem
```

### Trigger Workflow

1. **Nominal scenario**

```bash
curl http://localhost:8080/hello/Kevin?langageCode=es

## Get status
curl http://localhost:8080/hello/workflows/<workflow_id>/status
```

2. **LanguageCode sent as a signal**

```bash
curl http://localhost:8080/hello/Pores

## Sent a signal
curl http://localhost:8080/hello/workflows/<workflow_id>/langageCode/es

## Get status
curl http://localhost:8080/hello/workflows/<workflow_id>/status
```