# Development

How to build, test and run Flip Finder from source, with or without the
[homelab]. To just run it, see the [README](../README.md).

| Path | Contents |
| --- | --- |
| [`backend/`](../backend) | Spring Boot API and price importer (Java 25, Maven) |
| [`frontend/`](../frontend) | React web app (TypeScript, Vite) |
| [`chart/flipfinder/`](../chart/flipfinder) | Helm chart for the homelab |
| [`deploy/`](../deploy) | Argo CD Application that registers the chart |
| [`Dockerfile`](../Dockerfile) | Builds the web app and API into one image |

## Backend

Install the full JDK 25, not only the Java runtime, and check that both
commands report version 25:

```bash
java -version
javac -version
```

On Ubuntu or Debian-based systems, `sudo apt install openjdk-25-jdk`. Maven
itself is not needed: the Maven Wrapper downloads it on first use.

```bash
cd backend
./mvnw clean package
./mvnw spring-boot:run
```

On Windows, use `.\mvnw.cmd` in place of `./mvnw`.

The API starts on <http://localhost:8081>, with Swagger UI at
<http://localhost:8081/swagger-ui.html>. Port 8081 leaves 8080 free for the
homelab Gateway; set `SERVER_PORT` to use another. It imports prices from the
RuneScape Wiki when it starts and every five minutes after that.

`./mvnw verify` runs the tests. `spring-boot:run` serves only the API; the web
app comes from the frontend dev server below, or from the container.

### Profiles and the database

[`schema.sql`](../backend/src/main/resources/schema.sql) defines the SQLite
database, and a Spring profile chooses where it lives:

- `local` (the default) keeps it in memory, so each restart starts empty and
  re-imports within a second. It keeps two days of
  [trading history](homelab.md#trading-history), fetched within about 15
  seconds of starting.
- `homelab` stores it in the file at `FLIPFINDER_DB_PATH`, `/data/flipfinder.db`
  by default, on the homelab's persistent volume. It also turns on the
  endpoints that receive data from the RuneLite plugin
  (`flipfinder.runelite.enabled`), keeps
  [trading history](homelab.md#trading-history) (`flipfinder.history.enabled`),
  and turns on the chat (`flipfinder.llm.enabled`, from `FLIPFINDER_LLM_ENABLED`),
  which calls the language model at `FLIPFINDER_LLM_URL`
  (`flipfinder.llm.base-url`). The chat needs both: set
  `FLIPFINDER_LLM_ENABLED=false` to run the homelab profile without it.

Their settings are in
[`application-local.properties`](../backend/src/main/resources/application-local.properties)
and
[`application-homelab.properties`](../backend/src/main/resources/application-homelab.properties).
Each import is written in one transaction, which keeps imports on disk to
about half a second.

To work on the chat, run the homelab profile against the homelab's language
model through its Gateway:

```bash
SPRING_PROFILES_ACTIVE=homelab FLIPFINDER_DB_PATH=flipfinder.db \
  FLIPFINDER_LLM_URL=http://llm.localhost:8080/v1 ./mvnw spring-boot:run
```

The homelab runs its model only while an application
[asks for it](homelab.md#starting-and-stopping-the-model), and a local backend
does not, so start Flip Finder on the homelab too, or the model may be stopped.
If it is starting, a question waits for it.

The tests replace the model with a stub server, so `./mvnw verify` needs no
homelab.

## Frontend

Install Node.js 24 with npm, start the backend as above, and in a second
terminal:

```bash
cd frontend
npm install
npm run dev
```

Open <http://localhost:5173>. The Vite dev server reloads on save and forwards
`/api` and Swagger requests to the backend at `http://localhost:8081`.

To work on the frontend against the homelab's backend instead of a local one,
point the dev server at it:

```bash
FLIPFINDER_API_URL=http://flipfinder.localhost:8080 npm run dev
```

In PowerShell, set it with `$env:FLIPFINDER_API_URL = "..."` first.

`npm test` runs the unit tests and `npm run build` makes a type-checked
production build.

## RuneLite plugin

[Flip Finder Agent](https://github.com/griffinseibold/flip-finder-plugin), the
RuneLite plugin that reports account data to the homelab version, lives in its
own repository and is
[submitted to the Plugin Hub](https://github.com/runelite/plugin-hub/pull/17204).

## Container

The Dockerfile builds the frontend, bundles it into the Spring Boot jar and
runs every test on the way. To build and run your working copy:

```bash
docker build --tag flipfinder:local .
docker run --rm --publish 8081:8080 flipfinder:local
```

The container runs as a non-root user and needs a writable `/tmp` for the
SQLite native library. To try the persistent `homelab` profile locally, give
it a volume:

```bash
docker run --rm --publish 8081:8080 \
  --env SPRING_PROFILES_ACTIVE=homelab \
  --env FLIPFINDER_DB_PATH=/data/flipfinder.db \
  --volume flipfinder-data:/data \
  flipfinder:local
```

To try a build on the homelab itself, see
[Homelab deployment](homelab.md#running-a-local-build).

## CI and releases

[CI](../.github/workflows/ci.yaml) runs the backend tests, frontend tests and
Helm chart lint on every pull request and push. Pushes also publish the image
to `ghcr.io/griffinseibold/osrs-flip-finder`:

| Push | Image tags |
| --- | --- |
| To `master` | `master` and `sha-<commit>` |
| A version tag such as `v1.2.3` | `1.2.3` and `latest` |

The homelab runs the version named in the chart, so a release is what ships
new code there:

1. Bump `version`, `appVersion` and `image.tag` in
   [`chart/flipfinder`](../chart/flipfinder) to the new version and commit.
2. Tag the commit and push only the tag, for example
   `git tag -a v1.2.3 -m "Release 1.2.3" && git push origin v1.2.3`.
3. When CI has published the image, push the commit to `master`. Argo CD then
   rolls the homelab onto it.

Pushing the tag before `master` means the homelab never points at an image
that does not exist yet.

[homelab]: https://github.com/griffinseibold/Homelab
