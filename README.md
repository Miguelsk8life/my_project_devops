# my_project_devops

Учебный проект по курсу DevOps, доработанный как портфолио-проект для демонстрации практических навыков: контейнеризация, развёртывание в Kubernetes, CI/CD, централизованное логирование и базовая security-гигиена.

Приложение — простой Spring Boot сервис на Java 17, изначально развёрнутый через Docker Compose (веб-приложение + Loki + Promtail + Grafana). Проект эволюционировал в полноценное развёртывание на Kubernetes с автоматизированным CI/CD-пайплайном.

## Содержание

- [Архитектура](#архитектура)
- [Предварительные требования](#предварительные-требования)
- [Docker](#docker)
- [Kubernetes](#kubernetes)
- [Развёртывание](#развёртывание)
- [CI/CD](#cicd)
- [Логирование](#логирование)
- [Troubleshooting](#troubleshooting)
- [Известные ограничения](#известные-ограничения)
- [Технологии](#технологии)

## Архитектура

```
GitHub push (main)
        │
        ▼
  GitHub Actions
   ├─ Maven build + test
   ├─ Docker build (multistage)
   ├─ Push → GHCR (приватный, тег = commit SHA)
   └─ Автообновление k8s/app-deployment.yaml новым тегом,
      коммит обратно в main с [skip ci]
        │
        │ image pull (imagePullSecret)
        ▼
┌──────────────────────────────────────────────┐
│         Kubernetes (namespace: devops-portfolio)        │
│                                                │
│   Ingress (NGINX Ingress Controller)          │
│   ├─ web-app.local  → Service web-app:8080    │
│   └─ grafana.local  → Service grafana:3000    │
│                                                │
│   Deployment web-app (replicas: 1, до 2 для   │
│   демонстрации rolling update / self-healing) │
│   ├─ readinessProbe / livenessProbe (Actuator)│
│   ├─ imagePullSecrets: ghcr-cred (Secret)     │
│   └─ resources: requests/limits               │
│                                                │
│   Deployment loki   (эфемерное хранилище)     │
│   Deployment grafana (Secret + ConfigMap)     │
│   DaemonSet alloy (1 под на узел)             │
│   ├─ ServiceAccount + Role + RoleBinding      │
│   └─ читает логи подов через Kubernetes API   │
└──────────────────────────────────────────────┘
```

Полный путь логов: `web-app` (stdout) → Alloy (обнаружение подов через Kubernetes API, не через Docker socket) → Loki (push API) → Grafana (datasource, LogQL-запросы).

## Предварительные требования

- **Docker Desktop** с включённым Kubernetes (backend WSL2 на Windows).
- Для машин с ограниченными ресурсами (4 CPU / 6GB RAM и меньше) — файл `%USERPROFILE%\.wslconfig`:
  ```ini
  [wsl2]
  memory=3GB
  processors=3
  swap=2GB
  ```
  После изменения: `wsl --shutdown` и перезапуск Docker Desktop.
- `kubectl` (устанавливается вместе с Docker Desktop).
- **NGINX Ingress Controller**, установленный отдельно (см. раздел Kubernetes).
- GitHub **Personal Access Token (classic)** со scope `read:packages` — для `imagePullSecret`, так как образ в GHCR приватный.
- Две записи в `hosts`-файле Windows (`C:\Windows\System32\drivers\etc\hosts`, редактировать от имени администратора):
  ```
  127.0.0.1 web-app.local
  127.0.0.1 grafana.local
  ```

## Docker

Multistage-сборка (`app/Dockerfile`):

```dockerfile
FROM maven:3.8.6-eclipse-temurin-17 AS builder
WORKDIR /build
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
```

Финальный образ — JRE Alpine, без Maven и исходного кода. Флаг `-Djava.security.egd=file:/dev/./urandom` — не декоративный, а исправление реальной проблемы (см. Troubleshooting).

**Версионирование образов**: каждый push в `main` публикует образ в GHCR с двумя тегами — `github.sha` (неизменяемый, используется в манифестах Kubernetes) и `latest` (для удобства). Развёртывание всегда ссылается на конкретный SHA, а не на `latest`, что даёт воспроизводимость: манифест точно говорит, какой коммит сейчас запущен.

**Реестр**: GitHub Container Registry, приватный — `ghcr.io/miguelsk8life/my_project_devops`. Приватность образа не скрывает исходный код (он публичен), а демонстрирует паттерн аутентификации к приватному реестру из кластера (`imagePullSecret`), который встречается в корпоративной практике.

## Kubernetes

Манифесты — в `k8s/`, без Helm/Kustomize (сознательное решение — на масштабе одного приложения это была бы избыточная сложность).

| Файл | Назначение |
|---|---|
| `namespace.yaml` | Изолированный namespace `devops-portfolio` |
| `app-deployment.yaml`, `app-service.yaml` | Основное приложение: Deployment + ClusterIP Service |
| `loki-deployment.yaml`, `loki-service.yaml` | Loki с конфигурацией по умолчанию, без PVC (см. ниже) |
| `grafana-configmap.yaml`, `grafana-deployment.yaml`, `grafana-service.yaml` | Grafana, datasource как ConfigMap, пароль как Secret |
| `alloy-rbac.yaml`, `alloy-configmap.yaml`, `alloy-daemonset.yaml` | Агент логирования (см. раздел Логирование) |
| `ingress.yaml` | Маршрутизация по hostname |

**Health checks**: приложение изначально не имело Spring Boot Actuator. Добавлена зависимость `spring-boot-starter-actuator` — при работе внутри Kubernetes Spring Boot автоматически обнаруживает окружение (через переменную `KUBERNETES_SERVICE_HOST`) и включает `/actuator/health/liveness` и `/actuator/health/readiness` без дополнительной конфигурации.

**Secrets, не хранящиеся в Git**: `ghcr-cred` (docker-registry secret для imagePullSecret) и `grafana-admin-secret` создаются императивно:

```bash
kubectl create secret docker-registry ghcr-cred \
  --docker-server=ghcr.io \
  --docker-username=<github-username> \
  --docker-password=<PAT с read:packages> \
  --namespace=devops-portfolio

kubectl create secret generic grafana-admin-secret \
  --from-literal=admin-password='<пароль>' \
  --namespace=devops-portfolio
```

**Персистентность**: ни Loki, ни Grafana не используют `PersistentVolumeClaim`. Это осознанное решение, а не упущение — в исходном `docker-compose.yml` у этих сервисов тоже не было volume-монтирования, то есть логи и так были эфемерными. Копирование того же поведения в Kubernetes сохраняет паритет с исходной архитектурой без добавления сложности, которая не была нужна изначально.

**Ingress Controller** — устанавливается отдельно (не наш собственный манифест, а официальный от проекта kubernetes/ingress-nginx):

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.15.1/deploy/static/provider/cloud/deploy.yaml
```

Выбран NGINX как наиболее стандартный вариант с широкой документацией. Docker Desktop автоматически пробрасывает Service типа `LoadBalancer` на `localhost`, поэтому дополнительная настройка не требуется.

## Развёртывание

```bash
kubectl apply -f k8s/namespace.yaml
# создать Secrets (см. выше)
kubectl apply -f k8s/app-deployment.yaml -f k8s/app-service.yaml
kubectl apply -f k8s/loki-deployment.yaml -f k8s/loki-service.yaml
kubectl apply -f k8s/grafana-configmap.yaml -f k8s/grafana-deployment.yaml -f k8s/grafana-service.yaml
kubectl apply -f k8s/alloy-rbac.yaml -f k8s/alloy-configmap.yaml -f k8s/alloy-daemonset.yaml
kubectl apply -f k8s/ingress.yaml
```

Проверка:

```bash
kubectl get pods -n devops-portfolio
kubectl get deployments -n devops-portfolio
kubectl get services -n devops-portfolio
kubectl rollout status deployment/web-app -n devops-portfolio
```

Демонстрация rolling update / self-healing:

```bash
kubectl scale deployment/web-app --replicas=2 -n devops-portfolio
# ... наблюдать rolling update, self-healing ...
kubectl scale deployment/web-app --replicas=1 -n devops-portfolio
```

## CI/CD

Workflow `.github/workflows/ci.yml`:

```
git push (main)
     ↓
Checkout → Maven build/test → Docker build
     ↓
docker login ghcr.io (GITHUB_TOKEN, packages:write)
     ↓
docker push (:sha и :latest)
     ↓
sed обновляет image: в k8s/app-deployment.yaml
     ↓
git commit "chore: update web-app image to <sha> [skip ci]"
     ↓
git push (обратно в main)
     ↓
Telegram-уведомление
```

Ключевые решения:

- **`permissions: contents: write`** — минимально необходимое расширение прав `GITHUB_TOKEN` для автокоммита; не требует дополнительного PAT.
- **`[skip ci]`** в сообщении автокоммита — без этого пуш workflow обратно в `main` вызвал бы бесконечный цикл запусков.
- **Push в GHCR только на событии `push`, не на `pull_request`** — сборка и тесты выполняются на PR, но публикация образа — только после мержа в `main`.
- **Почему не полный `kubectl apply` из CI**: кластер Kubernetes выполняется локально (Docker Desktop), а GitHub Actions — в облаке GitHub. Раннер в облаке не может напрямую обратиться к кластеру за домашней сетью без дополнительной инфраструктуры (self-hosted runner). Решение — CI обновляет манифест и коммитит изменение; применение к кластеру (`kubectl apply`) выполняется вручную. Это честный компромисс для локального кластера одного разработчика, а не полный GitOps-цикл.

## Логирование

Изначально Promtail (Docker Compose) использовал `docker_sd_configs` и монтирование `/var/run/docker.sock`. При переносе в Kubernetes от этого подхода намеренно отказались:

1. Promtail достиг **End-of-Life 2 марта 2026 года** — больше не получает обновлений и патчей безопасности. Заменён на **Grafana Alloy**.
2. Alloy обнаруживает поды через **Kubernetes API** (`loki.source.kubernetes`), а не через монтирование файловой системы узла — не требует `hostPath` или privileged-доступа.
3. RBAC ограничен минимально необходимым: `Role` (не `ClusterRole`) в пределах одного namespace, только `get/list/watch` на `pods` и `get` на `pods/log`.

Пример LogQL-запроса в Grafana Explore:

```
{namespace="devops-portfolio", pod=~"web-app.*"}
```

## Troubleshooting

Реальные инциденты, с которыми столкнулись при развёртывании (не гипотетические — каждый воспроизведён и исправлен):

**Под `web-app` не проходит liveness/readiness probe, `connection refused`.**
Причина: классическая проблема нехватки энтропии (`SecureRandom`) при инициализации Tomcat внутри контейнера — время старта доходило до 76 секунд вместо ожидаемых 2–5. Исправление: флаг JVM `-Djava.security.egd=file:/dev/./urandom` в `ENTRYPOINT` — снизил время старта до ~13 секунд.

**Alloy не собирает логи, хотя запускается без ошибок.**
Причина: в конфигурации Alloy использовалась переменная `sys.env("HOSTNAME")` в надежде получить имя узла Kubernetes — но `HOSTNAME` внутри контейнера возвращает имя **пода**, а не узла. В результате фильтр `spec.nodeName=...` не находил ни одного пода. Исправление: имя узла получено через Downward API (`fieldRef: fieldPath: spec.nodeName`) в переменную `NODE_NAME`.

**Периодические рестарты `web-app` (`context deadline exceeded`, затем `connection refused`) после добавления стека наблюдаемости.**
Причина: нехватка ресурсов на локальной машине (4 CPU / 6GB RAM) при одновременном выполнении 5 компонентов (web-app ×2, loki, grafana, alloy). Решения: увеличение `timeoutSeconds` у проб, тюнинг `.wslconfig` для WSL2, и снижение `replicas` до 1 для повседневной работы (масштабирование до 2 — только для демонстрации).

**GHCR-пакет опубликовался как `Public`, хотя ожидалась приватность.**
GHCR иногда делает пакет публичным по умолчанию при первой публикации через Actions независимо от scope токена. Исправлено вручную через Package settings → Danger Zone → Change visibility.

**Предупреждения `LF will be replaced by CRLF` при `git add`.**
Безвредно — связано с `core.autocrlf` в Git для Windows, нормализующим переносы строк. Не влияет на содержимое файлов.

## Известные ограничения

Честно о том, что **не** реализовано, и почему:

- Контейнер `web-app` всё ещё выполняется от `root` — non-root `USER` в Dockerfile не добавлен (следующий шаг для security-гигиены).
- `readOnlyRootFilesystem` и `securityContext` для подов не настроены.
- HPA (Horizontal Pod Autoscaler) не реализован — для одного Deployment с фиксированной нагрузкой не было практической необходимости.
- Полный GitOps-цикл (автоматический `kubectl apply` из CI) не реализован — требует self-hosted runner с доступом к локальному кластеру (см. раздел CI/CD).

## Технологии

Java 17 · Spring Boot 3.2.5 · Maven · Docker (multistage builds) · Kubernetes (Deployment, Service, ConfigMap, Secret, Ingress, RBAC, DaemonSet, Namespace) · GitHub Actions · GitHub Container Registry · Grafana Loki · Grafana Alloy · Grafana · NGINX Ingress Controller
