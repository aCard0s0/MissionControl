# Mission Control — Testing Conventions

How the backend tests are built, and why. Written down because most of the code worth testing
here sits behind a boundary — a Docker daemon, a provider API, an async executor, a database —
and the four patterns below are what make that code reachable without one.

The four patterns below are backend-only. The frontend runs Vitest through the Angular
`@angular/build:unit-test` builder; its coverage setup and the traps its component tests keep
falling into are at the end of this file.

## The rule these follow

**Every guard sits above a boundary call, so make the boundary substitutable — not the guard.**

An ownership check, an admission cap, a validation rule and a rollback are all decisions taken
*before* something irreversible happens. If the irreversible part is unreachable in a test, so is
the decision. One narrow seam at the boundary makes all of them testable at once.

Prefer collaborators injected through the constructor. When a boundary is constructed inline
(`HttpClient` fields, a `ProcessBuilder`, a `/proc` read), expose **one** package-private,
non-static method wrapping it and say in a comment why it is not private — otherwise the next
person tidies it back and takes the tests with it.

## 1. Substitutable boundary call

The method that talks to the outside world is package-private and non-static, so a test
subclasses the class and answers it from a canned function.

```java
// ComposeStackManager
/**
 * Runs the Docker CLI. Package-private and non-static so a test can substitute it: every
 * ownership guard in this class sits above this call, and none of them is reachable
 * otherwise without a real daemon and real foreign resources to refuse to destroy.
 */
String run(List<String> command, Map<String, String> environment, Duration timeout) { … }
```

```java
// the test
private ComposeStackManager managerReturning(Function<List<String>, String> responder) {
  return new ComposeStackManager(hosts, stackDirectory.toString()) {
    @Override
    String run(List<String> command, Map<String, String> environment, Duration timeout) {
      commands.add(command);
      return responder.apply(command);
    }
  };
}
```

Recording the command list is half the value: the arguments a Compose operation runs
(`rm --stop --force`, `stop --timeout 10`) are behaviour, not implementation detail.

In use: `ComposeStackManager.run`, `ModelCatalogService.send`,
`McpHealthProbe.ownNetworkContainerId`.

**Still test the seam itself.** A substituted method is untested by definition, so exercise it
directly — `ComposeStackManager.run` against `/bin/sh` (exit codes, timeout kill, unstartable
binary; guard those with `@EnabledOnOs({OS.LINUX, OS.MAC})`), `ModelCatalogService.send` against
a loopback server.

## 2. Loopback `HttpServer`, not a mocked `HttpClient`

For outbound HTTP, bind a real JDK `HttpServer` on port 0. No dependency, no mocking of a
fluent API, and status codes, content types and headers all behave like the real thing.

```java
@BeforeEach
void start() throws IOException {
  server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
  server.start();
  baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
}

@AfterEach
void stop() {
  server.stop(0);
}

private void route(String path, int status, String body) {
  server.createContext(path, exchange -> {
    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
    bodies.add(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
    respond(exchange, status, body);   // sendResponseHeaders(status, -1) for an empty body
  });
}
```

For "the host is unreachable", open a `ServerSocket(0)`, take its port and close it — a
connection there is refused immediately, with no DNS lookup and no waiting.

In use: `ModelProviderServiceOllamaTest`, `McpHealthProbeRequestTest`, `ModelCatalogLiveTest`.

## 3. Queue-only `ExecutorService` for async orchestration

When a call records intent and hands the work to an executor, the rules that decide *whether* it
is handed off are the interesting part. An executor that records tasks without running them makes
them observable, with no daemon and no waiting.

```java
private static final class QueuedOperations extends AbstractExecutorService {
  private final List<Runnable> tasks = Collections.synchronizedList(new ArrayList<>());
  @Override public void execute(Runnable command) { tasks.add(command); }
  int queued() { return tasks.size(); }
  // shutdown/isShutdown/isTerminated/awaitTermination: trivial
}
```

Then assert on the row the caller wrote (`desired_state`, `operation_state`,
`applied_revision`) and on `queued()`. Cover the queued work itself in a separate test that
calls those methods directly — `McpRegistryLifecycleTest` (rules) and
`McpComposeLifecycleTest` (the work) are that split.

A same-thread executor is the alternative when the work *is* what you are testing and its own
collaborators are already substituted.

## 4. Real SQLite over a mocked repository

Anything with SQL semantics — a UNIQUE constraint, a CHECK, an ordering, an upsert — is tested
against the production schema via `SqliteTestDatabase.open()`, not a mocked repository. It costs
milliseconds and it catches what a mock cannot: `service_key TEXT UNIQUE`, the
`kind = 'managed' AND host_id IS NOT NULL` check, `ORDER BY name COLLATE NOCASE`.

One `@AfterEach`, in this order: release the service's executor, *then* close the database.
JUnit 5 does not order sibling teardown methods, so put both in one method.

## What to test, and what to leave alone

**Worth covering, in this order:**

1. Anything that decides whether an irreversible thing happens — ownership checks, admission
   caps, deletion guards, immutability rules.
2. Reject rules on input that reaches a shell, a Compose file, a mount path or a URL. Write the
   *rejections*; a validator whose happy path is green tells you nothing.
3. Rollback and cleanup paths. Untested rollback code is rollback code that does not work.
4. Failure mapping — which exception becomes 400 vs 503 vs 500, and whether the operator-facing
   message names the thing that failed.

**Not worth chasing:** classes that are a list of one-line delegations (`HermesProfiles` is
~25 pass-throughs to `writeProfileFile`). They can carry hundreds of missed instructions and
cover for free without proving anything. Coverage percentage is a smoke detector, not a goal —
when a threshold and a delegation-heavy class disagree, exclude the class rather than writing
tests that assert a mock was called.

## The one test that runs the whole application

`HttpSurfaceTest` boots the app (`@SpringBootTest` on a random port, `@ActiveProfiles("test")`,
so `mc.startup-reconcile: false` keeps startup away from any daemon) and asserts what only the
assembled stack decides:

- **No mutating route answers 5xx to a body it should reject.** This is a sweep, not a list: it
  walks every POST/PUT/PATCH/DELETE the application maps, sends an empty body and `{}`, and fails
  on any 5xx. A 500 there reports a client mistake as a Mission Control defect, logs a stack trace
  at ERROR, and pages whoever watches the 5xx rate — that is how the `transport` NPE behaved
  before it was fixed. New endpoints are covered the day they are added, with nothing to update.
- **The SPA fallback and the API do not shadow each other** — a deep link gets `index.html`, an
  unknown `/api/...` path gets 404, and `/health` and `/config.js` reach their controllers. That
  needs the real resource chain; calling the resolver directly cannot see it.
- **CORS still admits the dev origin** and refuses others, because losing that breaks `ng serve`
  while the combined image keeps working.
- **The terminal's origin guard is attached to its endpoint**, proved over a real WebSocket
  handshake against the running server. `TerminalOriginGuardTest` proves the rule; this proves it
  is installed — the endpoint hands out an interactive shell and there is no authentication
  anywhere in this application.

It needs `src/test/resources/static/index.html`, a stand-in for the Angular build that the image
copies into `classpath:/static` at build time. Without it the fallback resolves to nothing and the
deep-link assertions cannot run.

## The tests that need a real Docker daemon

Tagged `docker`, excluded from `mvn test`, and run as their own CI job:

```bash
mvn test -Dgroups=docker -Dsurefire.excludedGroups= -Djacoco.skip=true
```

`ComposeStackDockerAcceptanceTest` exists because the mocked tier pins the argv Mission Control
builds and never asks Docker whether it accepts it. If `compose rm --stop --force` carried a wrong
flag, or `--pull always` were unsupported by the installed Compose, the whole 1042-test suite would
still pass. It drives one managed MCP record through provision → start → stop → delete → purge and
asserts against the daemon (queried with plain `docker` commands, deliberately not the code under
test) that the container appears stopped, comes up, goes down, disappears, and that **the named
volume survives the delete and only goes on an explicit purge**. It also proves the ownership
guard's `{{ index .Labels "io.hermes.mission-control.owner" }}` format string reads real
`docker inspect` output — including what an *unlabelled* resource returns, which is the case that
decides whether Mission Control adopts somebody else's volume.

Safety, since the Compose project name is a production constant and cannot be isolated: the test
never names anything it did not create. The service key carries a random suffix, the stack file
goes to a temp directory, the record lives in a throwaway in-memory database (so the host render
describes only that one service), and teardown removes only that container and volume. It never
runs `compose down` and never passes `--remove-orphans` — either would take a real deployment's
services with it.

Two assumptions make it skip rather than fail where it cannot run: no daemon/Compose plugin, and
no registry access for the one flow that pulls (on macOS that is usually a locked keychain
blocking the credential helper — `security -v unlock-keychain ~/Library/Keychains/login.keychain-db`).

## Parsers pinned to captured hermes output

`hermes status`, the gateway log, the bundled manifest and SKILL.md frontmatter are all read by
parsers that were written against text we typed ourselves. `HermesSetupTest` says so in its own
javadoc: the fixtures prove the rules we believe hermes follows, not what it prints. A release can
move a section heading or rename a row label, and every one of those tests stays green while the
dashboard reports a configured agent as unconfigured.

`HermesCliFixtureTest` closes that by provenance rather than by mocking. Capture real output:

```bash
./tools/capture-hermes-fixtures.sh <container> [profile]
```

The script reads only (`hermes --version`, `hermes status`, `cat`), redacts what belongs to the
operator rather than to the output format, and writes into
`src/test/resources/fixtures/hermes-<version>/`. The test parses every set present and asserts the
parse is **non-degenerate** — sections yield rows, ✓/✗ resolves, indented detail lines stay detail,
the frontmatter name beats the directory. A changed format makes these readers return empty rather
than throw, and empty is the failure that is otherwise invisible.

On a hermes bump: re-capture, read the diff, keep the old set. Drift then arrives as a reviewable
diff and a failing test instead of a silent misread. With no fixtures present the test skips and
names the script, so the mechanism is inert until someone captures.

`fixtures/README.md` records what each file pins. One output is deliberately absent: `hermes mcp
test` is the only command here with a side effect — it opens a connection and can start a stdio
server inside the agent — so its parsing stays covered by synthetic output only.

## Test names and comments

Names are sentences describing the rule, not the method under test:
`aForeignNetworkStopsTheStackBeforeAnythingIsWritten`,
`aStopMustLeaveThePendingChangePending`,
`aSecretThatNoLongerDecryptsIsLeftOutRatherThanWrittenAsAnEmptyVariable`.

Where a test protects something subtle, the comment says what breaks without it — the failure,
not the mechanics:

```java
// an empty ANTHROPIC_API_KEY in .env is worse than a missing one: the agent starts and fails
// its first call with an auth error instead of saying the key is not configured
```

## Coverage — backend

JaCoCo runs on `mvn test` (the report goal is rebound from `verify`, because CI runs bare
`mvn test`). The CSV is at `target/site/jacoco/jacoco.csv`, the HTML at
`target/site/jacoco/index.html`.

`jacoco:check` runs in the same phase and **fails the build on a regression**. Its minimums are a
ratchet, each sitting just under what the suite covers today, so a refactor passes and a
regression does not. Raise them after a deliberate push; do not lower them to make a build green.

Three things to know when running it locally:

- A subset run (`mvn test -Dtest=Foo`) covers almost nothing, so the gate fails at the end even
  though the tests passed. Add `-Djacoco.skip=true` while iterating.
- `<append>false</append>` is set on the agent. The default appends, which meant a partial run
  left its coverage in `jacoco.exec` and inflated the next report — every number quoted from a
  dirty `target/` was wrong. Still prefer `mvn clean test` for anything you intend to quote.
- `target/surefire-reports/` keeps XML files from renamed or deleted test classes forever, so
  summing them over a dirty `target/` overcounts. Trust surefire's own `Tests run:` line.

## Coverage — frontend

```bash
npm run test:coverage   # in applications/mission-control-fe
```

Vitest's v8 provider, configured on the `test` target in `angular.json` rather than in a
runner config file, so `ng test` stays the single entry point. Reports land in
`coverage/mission-control/` — `index.html` to browse, `lcov.info` for editors, and
`coverage-summary.json`, which is what CI reads for its step summary. Plain `npm test` leaves
coverage off, so the watch loop stays fast.

Two settings do the load-bearing work:

- `coverageInclude: ["src/**/*.ts"]` — without it, V8 only reports files a test happened to
  import, so a page with no spec at all is *absent* from the report rather than counted at
  zero. That turns the total into a measure of the files you already test.
- `coverageExclude: ["src/**/*.html"]` — Angular compiles templates into instructions that V8
  maps back to the `.html`, so an untested template lands as several hundred missed lines of
  markup. Excluding them makes the number mean *TypeScript logic covered*, comparable to the
  backend's JaCoCo line figure. Drop the entry to count render paths instead; expect the total
  to fall sharply until component tests exist.

`coverageThresholds` fails the run below its minimums, the same ratchet the backend gets from
`jacoco:check`. Each minimum sits a point or two under what the suite covers today, so a refactor
passes and a regression does not. Raise them after a deliberate push; do not lower them to make a
build green. CI runs `npm run test:coverage`, so the gate runs there without a separate step.

The minimums are lower than the backend's 95% and will stay lower for a while: the pages under
`src/app/pages/` are mostly template, and their TypeScript is thin. The number to watch is
whether it moves down, not whether it reaches a target.

## Frontend component tests

Rendering tests go through `TestBed` with a host component, and reach into the DOM the way an
operator reaches the page — a button by its label, a field by its `<label>`, a row by its class.
Two things trip up every one of them:

- **`whenStable()` does not settle a promise the harness does not know about.** A store call in an
  `effect` or a click handler is invisible to Angular's pending-task registry, so a spec holding
  fake timers settles with `await vi.advanceTimersByTimeAsync(0)` followed by `detectChanges()`.
  `[(ngModel)]` needs the same beat before the DOM carries the new value.
- **`data-reveal` animates through gsap on a real timer.** A tween started by a test that does not
  hold the clock finishes after jsdom has torn the document down, and reads `getComputedStyle` off
  a window that no longer exists — reported as an unhandled error, not a failure, so the suite
  still says every test passed while the run exits non-zero. Any spec that renders a page freezes
  the clock for the whole file.

A required `input()` is not bound until after construction, so a component that reads one to load
something reads it in an `effect`, not its constructor. A spec that renders it through a host is
what catches the difference — the template compiler cannot.
