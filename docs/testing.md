# Mission Control — Backend Testing Conventions

How the backend tests are built, and why. Written down because most of the code worth testing
here sits behind a boundary — a Docker daemon, a provider API, an async executor, a database —
and the four patterns below are what make that code reachable without one.

Frontend testing (Angular/Karma) is not covered here.

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

## Coverage

JaCoCo runs on `mvn test` (the report goal is rebound from `verify`, because CI runs bare
`mvn test`). The CSV is at `target/site/jacoco/jacoco.csv`, the HTML at
`target/site/jacoco/index.html`.

Two traps when reading local numbers:

- The agent **appends** to `jacoco.exec`, so a partial `mvn test -Dtest=Foo` inflates the next
  report. Use `mvn clean test` for any number you intend to quote.
- `target/surefire-reports/` keeps XML files from renamed or deleted test classes forever, so
  summing them over a dirty `target/` overcounts. Trust surefire's own `Tests run:` line.
