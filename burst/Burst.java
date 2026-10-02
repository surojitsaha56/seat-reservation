/*
 * Burst: one-command load + correctness check for the seat reservation API.
 *
 *   java burst/Burst.java <BASE_URL> [options]          (JDK 21+, nothing else to install)
 *   ./burst.sh <BASE_URL> [options]                     (wrapper, checks the java version)
 *
 * Options (each also accepted as --name=value):
 *   --seats N            seats in the general-stampede show              (default 1000)
 *   --stampede N         requests in the general stampede                (default 20000)
 *   --users N            distinct users in the general stampede          (default 4000)
 *   --hot-users N        users that all fight for each hot seat          (default 500)
 *   --hot-seats N        number of hot seats, stormed concurrently       (default 5)
 *   --max-inflight N     max concurrent requests in the stampede         (default 500)
 *   --admin-token T      X-Admin-Token for POST /shows (env ADMIN_TOKEN) (default dev-admin-token)
 *   --timeout-seconds N  per-request client timeout                      (default 60)
 *   --seed N             RNG seed for the stampede plan                  (default 42)
 *
 * Scenarios (each on its own fresh show):
 *   a. hot-seat storm   b. general stampede   c. idempotency   d. per-user limit   e. identity   f. reconciliation
 *
 * Exit code 0 only when every check passes. Checks: zero 5xx, one winner per hot seat, only allowed outcomes,
 * one reservation per idempotency key, show invariants (available+held+confirmed == total, held == 0, confirmed
 * matches what the client saw), and /actuator/prometheus counter deltas equal the client's own tally.
 *
 * Client-side errors (connect failure, timeout, reset) are kept in their own bucket and never counted as 5xx.
 *  - A connect failure means the request never reached the server (typically an OS accept-backlog artifact,
 *    worst on Windows), so it is retried up to 3 more times with backoff. Persistent ones are reported.
 *  - Other client errors (timeout, reset) have an UNKNOWN server outcome and are never retried. Reconciliation
 *    allows exactly that many units of slack (printed), so an unknown request cannot hide a real discrepancy.
 *  - Any client error is a WARN. The run FAILS only if they exceed 1% of all requests, because then the
 *    checks are no longer meaningful. Hot-seat winners are never excused: no winner seen is a FAIL.
 *
 * Metric deltas assume nothing else is hitting the service during the run (true for a fresh deploy).
 */
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Burst {

    // ---------------------------------------------------------------- configuration

    static String baseUrl;
    static int seats = 1000, stampede = 20000, users = 4000, hotUsers = 500, hotSeats = 5, maxInflight = 500;
    static int timeoutSeconds = 60;
    static long seed = 42;
    static String adminToken = System.getenv().getOrDefault("ADMIN_TOKEN", "dev-admin-token");
    /** Unique per run so user ids and keys never collide with an earlier run against the same database. */
    static final String RUN = Long.toString(System.currentTimeMillis(), 36);

    static void parseArgs(String[] args) {
        if (args.length == 0 || args[0].startsWith("--")) {
            System.err.println("usage: java burst/Burst.java <BASE_URL> [--seats N] [--stampede N] [--users N] "
                    + "[--hot-users N] [--hot-seats N] [--max-inflight N] [--admin-token T] "
                    + "[--timeout-seconds N] [--seed N]");
            System.exit(2);
        }
        baseUrl = args[0].replaceAll("/+$", "");
        for (int i = 1; i < args.length; i++) {
            String a = args[i], name = a, value = null;
            int eq = a.indexOf('=');
            if (eq > 0) { name = a.substring(0, eq); value = a.substring(eq + 1); }
            else if (i + 1 < args.length) value = args[++i];
            if (value == null) { System.err.println("missing value for " + a); System.exit(2); }
            switch (name) {
                case "--seats" -> seats = Integer.parseInt(value);
                case "--stampede" -> stampede = Integer.parseInt(value);
                case "--users" -> users = Integer.parseInt(value);
                case "--hot-users" -> hotUsers = Integer.parseInt(value);
                case "--hot-seats" -> hotSeats = Integer.parseInt(value);
                case "--max-inflight" -> maxInflight = Integer.parseInt(value);
                case "--admin-token" -> adminToken = value;
                case "--timeout-seconds" -> timeoutSeconds = Integer.parseInt(value);
                case "--seed" -> seed = Long.parseLong(value);
                default -> { System.err.println("unknown option " + name); System.exit(2); }
            }
        }
    }

    // ---------------------------------------------------------------- HTTP plumbing

    /** One HTTP exchange. Exactly one of (status > 0) or (clientError != null) holds. */
    record Resp(int status, String body, long nanos, String clientError) {
        boolean ok() { return clientError == null; }
        boolean is5xx() { return status >= 500; }

        /** Minimal JSON string-field extraction; our responses are flat and machine generated. */
        String field(String name) {
            Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body == null ? "" : body);
            return m.find() ? m.group(1) : null;
        }
    }

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    static final LongAdder connectRetries = new LongAdder();
    static final LongAdder serverErrors = new LongAdder();     // every 5xx seen on any call
    static final LongAdder httpCalls = new LongAdder();
    static final LongAdder clientErrorCalls = new LongAdder(); // calls that ended in a client-side error
    static final ConcurrentHashMap<String, LongAdder> clientErrorKinds = new ConcurrentHashMap<>();
    static final Queue<String> fiveXxSamples = new ConcurrentLinkedQueue<>();

    static HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(timeoutSeconds));
    }

    static Resp send(HttpRequest r) {
        httpCalls.increment();
        for (int attempt = 1; ; attempt++) {
            long t0 = System.nanoTime();
            try {
                HttpResponse<String> resp = CLIENT.send(r, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 500) {
                    serverErrors.increment();
                    if (fiveXxSamples.size() < 5) fiveXxSamples.add(resp.statusCode() + " " + resp.body());
                }
                return new Resp(resp.statusCode(), resp.body(), System.nanoTime() - t0, null);
            } catch (ConnectException | HttpConnectTimeoutException e) {
                // Never reached the server, so a retry cannot double-apply anything.
                if (attempt < 4) { // initial try + 3 retries
                    connectRetries.increment();
                    sleep(200L * attempt + ThreadLocalRandom.current().nextInt(200));
                    continue;
                }
                return clientError("connect", t0);
            } catch (HttpTimeoutException e) {
                return clientError("timeout", t0);
            } catch (IOException e) {
                return clientError("io:" + e.getClass().getSimpleName(), t0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return clientError("interrupted", t0);
            }
        }
    }

    static Resp clientError(String kind, long t0) {
        clientErrorCalls.increment();
        clientErrorKinds.computeIfAbsent(kind, k -> new LongAdder()).increment();
        return new Resp(0, null, System.nanoTime() - t0, kind);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ---------------------------------------------------------------- API helpers

    static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    /** Fetch tokens for all users up front (bounded concurrency) so the measured phases only do reserves. */
    static void prefetchTokens(Collection<String> userIds) {
        List<String> missing = userIds.stream().filter(u -> !TOKENS.containsKey(u)).toList();
        runBounded(missing.stream().<Callable<Void>>map(u -> () -> {
            Resp r = send(req("/auth/token").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"user_id\":" + q(u) + "}")).build());
            String t = r.status == 200 ? r.field("token") : null;
            if (t == null) throw new IllegalStateException("cannot get token for " + u + ": " + describe(r));
            TOKENS.put(u, t);
            return null;
        }).toList(), 200);
    }

    static String describe(Resp r) { return r.ok() ? r.status + " " + r.body : "client error " + r.clientError; }

    static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    static String seatList(List<String> seats) {
        return seats.stream().map(Burst::q).collect(Collectors.joining(",", "[", "]"));
    }

    /** What the client believes about one show; the reconciliation step compares it to the server. */
    static final class Ledger {
        final String showId, name;
        final int totalSeats;
        final Map<String, Integer> confirmed = new ConcurrentHashMap<>(); // reservation_id -> seat count (201s)
        final Set<String> cancelled = ConcurrentHashMap.newKeySet();
        final LongAdder unknown = new LongAdder();  // requests on this show with unknown server outcome

        Ledger(String showId, String name, int totalSeats) { this.showId = showId; this.name = name; this.totalSeats = totalSeats; }

        int expectedConfirmedSeats() {
            return confirmed.entrySet().stream().filter(e -> !cancelled.contains(e.getKey()))
                    .mapToInt(Map.Entry::getValue).sum();
        }
    }

    static final List<Ledger> LEDGERS = new CopyOnWriteArrayList<>();
    /** user|key -> distinct reservation ids returned by 200/201 replies (must never exceed one). */
    static final ConcurrentHashMap<String, Set<String>> KEY_RESERVATIONS = new ConcurrentHashMap<>();
    static final Tally ALL_RESERVES = new Tally("all reserves");
    static final Tally ALL_CANCELS = new Tally("all cancels");

    static Ledger createShow(String name, List<String> labels, int perUserLimit) {
        String body = "{\"name\":" + q(name + "-" + RUN) + ",\"seats\":" + seatList(labels)
                + ",\"price_paise\":10000,\"per_user_limit\":" + perUserLimit + "}";
        Resp r = send(req("/shows").header("Content-Type", "application/json").header("X-Admin-Token", adminToken)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        String id = r.status == 201 ? r.field("id") : null;
        if (id == null) throw new IllegalStateException("cannot create show: " + describe(r));
        Ledger l = new Ledger(id, name, labels.size());
        LEDGERS.add(l);
        return l;
    }

    static List<String> labels(String prefix, int n) {
        return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> prefix + i).toList();
    }

    /**
     * Reserve and book-keep. The key goes in the Idempotency-Key header or the body field (chosen by a hash of
     * the key, so retries of one logical request always use the same placement). extraJson injects additional
     * body fields such as a spoofed user_id.
     */
    static Resp reserve(Ledger l, Tally scenario, String user, String key, List<String> seatLabels, String extraJson) {
        boolean keyInBody = (key.hashCode() & 1) == 0;
        String body = "{\"seats\":" + seatList(seatLabels) + (keyInBody ? ",\"idempotency_key\":" + q(key) : "")
                + (extraJson == null ? "" : "," + extraJson) + "}";
        HttpRequest.Builder b = req("/shows/" + l.showId + "/reserve").header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TOKENS.get(user));
        if (!keyInBody) b.header("Idempotency-Key", key);
        Resp r = send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build());

        scenario.reserve(r);
        ALL_RESERVES.reserve(r);
        if (!r.ok()) l.unknown.increment();
        if (r.status == 201) l.confirmed.put(r.field("reservation_id"), seatLabels.size());
        if (r.status == 200 || r.status == 201) {
            KEY_RESERVATIONS.computeIfAbsent(user + "|" + key, k -> ConcurrentHashMap.newKeySet())
                    .add(r.field("reservation_id"));
        }
        return r;
    }

    static Resp cancel(Ledger l, Tally scenario, String reservationId, String user) {
        Resp r = send(req("/reservations/" + reservationId + "/cancel").header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TOKENS.get(user)).POST(HttpRequest.BodyPublishers.noBody()).build());
        scenario.cancel(r);
        ALL_CANCELS.cancel(r);
        if (!r.ok()) l.unknown.increment();
        if (r.status == 200) l.cancelled.add(reservationId);
        return r;
    }

    // ---------------------------------------------------------------- outcome tally and latency

    static final class Tally {
        final String name;
        final ConcurrentHashMap<String, LongAdder> buckets = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        final LongAdder total = new LongAdder();

        Tally(String name) { this.name = name; }

        void add(String bucket) { buckets.computeIfAbsent(bucket, k -> new LongAdder()).increment(); }

        long count(String bucket) { LongAdder a = buckets.get(bucket); return a == null ? 0 : a.sum(); }

        long clientErrors() {
            return buckets.entrySet().stream().filter(e -> e.getKey().startsWith("CLIENT-ERROR"))
                    .mapToLong(e -> e.getValue().sum()).sum();
        }

        long fiveXx() {
            return buckets.entrySet().stream().filter(e -> e.getKey().startsWith("5xx"))
                    .mapToLong(e -> e.getValue().sum()).sum();
        }

        void reserve(Resp r) {
            total.increment();
            latencies.add(r.nanos);
            if (!r.ok()) add("CLIENT-ERROR " + r.clientError);
            else if (r.status == 201) add("201 confirmed");
            else if (r.status == 200) add("200 idempotent replay");
            else if (r.status == 409) add("409 declined:" + r.field("reason"));
            else if (r.is5xx()) add("5xx " + r.status);
            else add("4xx-other " + r.status);
        }

        void cancel(Resp r) {
            total.increment();
            if (!r.ok()) add("CLIENT-ERROR " + r.clientError);
            else if (r.status == 200) add("200 cancelled");
            else if (r.status == 409) add("409 declined:" + r.field("reason"));
            else if (r.is5xx()) add("5xx " + r.status);
            else add("4xx-other " + r.status);
        }

        /** Print the distribution and (if any reserve calls were timed) latency percentiles. */
        void print(long wallNanos) {
            new TreeMap<>(buckets).forEach((k, v) -> System.out.printf("    %-34s %8d%n", k, v.sum()));
            if (!latencies.isEmpty()) {
                long[] a = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
                System.out.printf("    latency ms  p50=%.1f  p95=%.1f  p99=%.1f  max=%.1f%n",
                        ms(pct(a, 0.50)), ms(pct(a, 0.95)), ms(pct(a, 0.99)), ms(a[a.length - 1]));
            }
            double secs = wallNanos / 1e9;
            System.out.printf("    %d requests in %.2fs = %.0f req/s%n", total.sum(), secs, total.sum() / secs);
        }

        static long pct(long[] sorted, double p) { return sorted[Math.max(0, (int) Math.ceil(p * sorted.length) - 1)]; }
        static double ms(long nanos) { return nanos / 1e6; }
    }

    // ---------------------------------------------------------------- concurrency helpers

    /** Run tasks with at most maxInFlight running at once (semaphore), each on its own virtual thread. */
    static <T> List<T> runBounded(List<Callable<T>> tasks, int maxInFlight) {
        Semaphore permits = new Semaphore(maxInFlight);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> fs = new ArrayList<>();
            for (Callable<T> t : tasks) {
                permits.acquireUninterruptibly();
                fs.add(ex.submit(() -> { try { return t.call(); } finally { permits.release(); } }));
            }
            return collect(fs);
        }
    }

    /**
     * Park every task behind a latch and release them all at once, so they hit the server as close to
     * simultaneously as the client can manage. This is the "all hot users at the same instant" primitive.
     */
    static <T> List<T> runSimultaneously(List<Callable<T>> tasks) {
        CountDownLatch ready = new CountDownLatch(tasks.size()), go = new CountDownLatch(1);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> fs = new ArrayList<>();
            for (Callable<T> t : tasks) {
                fs.add(ex.submit(() -> { ready.countDown(); go.await(); return t.call(); }));
            }
            try { ready.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            go.countDown();
            return collect(fs);
        }
    }

    static <T> List<T> collect(List<Future<T>> fs) {
        List<T> out = new ArrayList<>();
        for (Future<T> f : fs) {
            try { out.add(f.get()); } catch (Exception e) { throw new IllegalStateException(e.getCause() != null ? e.getCause() : e); }
        }
        return out;
    }

    // ---------------------------------------------------------------- checks and reporting

    record Check(String section, String name, String status, String detail) {}
    static final List<Check> CHECKS = new ArrayList<>();

    static void check(String section, String name, boolean ok, String detail) {
        CHECKS.add(new Check(section, name, ok ? "PASS" : "FAIL", detail));
        System.out.printf("  [%s] %s  (%s)%n", ok ? "PASS" : "FAIL", name, detail);
    }

    static void warn(String section, String name, String detail) {
        CHECKS.add(new Check(section, name, "WARN", detail));
        System.out.printf("  [WARN] %s  (%s)%n", name, detail);
    }

    static void skipped(String section, String name, String detail) {
        CHECKS.add(new Check(section, name, "SKIPPED", detail));
        System.out.printf("  [SKIPPED] %s  (%s)%n", name, detail);
    }

    static void header(String title) { System.out.println(); System.out.println("=== " + title + " ==="); }

    /** Every reserve outcome must be one of the documented ones; anything else (esp. 5xx) is a failure. */
    static boolean onlyAllowed(Tally t, Set<String> allowed) {
        return t.buckets.keySet().stream().allMatch(allowed::contains);
    }

    static final Set<String> RESERVE_OUTCOMES = Set.of("201 confirmed", "200 idempotent replay",
            "409 declined:seat_taken", "409 declined:per_user_limit", "409 declined:idempotency_key_reused");

    /** Allowed outcomes plus any client-side error buckets (those are judged separately). */
    static Set<String> allowedPlusClientErrors(Tally t, Set<String> allowed) {
        Set<String> s = new HashSet<>(allowed);
        t.buckets.keySet().stream().filter(k -> k.startsWith("CLIENT-ERROR")).forEach(s::add);
        return s;
    }

    // ---------------------------------------------------------------- scenario a: hot-seat storm

    static void hotSeatStorm() {
        String sec = "a";
        header("(a) HOT-SEAT STORM: " + hotUsers + " users x " + hotSeats + " hot seats, all released at once");
        List<String> userIds = labels("hot-" + RUN + "-u", hotUsers);
        prefetchTokens(userIds);
        List<String> hot = labels("H", hotSeats);
        // limit == number of hot seats, so one user winning several seats is never declined for the limit
        Ledger show = createShow("hot", hot, hotSeats);
        Tally t = new Tally("hot");

        List<Callable<String[]>> tasks = new ArrayList<>();
        for (String seat : hot) {
            for (String u : userIds) {
                tasks.add(() -> {
                    Resp r = reserve(show, t, u, "hot-" + seat + "-" + u, List.of(seat), null);
                    return new String[] {seat, r.ok() ? String.valueOf(r.status) : "client-error",
                            r.ok() && r.status == 409 ? r.field("reason") : ""};
                });
            }
        }
        Collections.shuffle(tasks, new Random(seed)); // interleave the seats instead of storming one at a time
        long t0 = System.nanoTime();
        List<String[]> results = runSimultaneously(tasks);
        long wall = System.nanoTime() - t0;
        t.print(wall);

        for (String seat : hot) {
            long winners = results.stream().filter(r -> r[0].equals(seat) && r[1].equals("201")).count();
            long losers409 = results.stream().filter(r -> r[0].equals(seat) && r[1].equals("409")
                    && r[2].equals("seat_taken")).count();
            long unknown = results.stream().filter(r -> r[0].equals(seat) && r[1].equals("client-error")).count();
            // Unknown outcomes cannot be a second winner: the server-side counts in (f) prove the winner count.
            // They only matter if no winner was seen at all (winners == 0 fails).
            check(sec, "hot seat " + seat + ": exactly one 201, rest 409 seat_taken",
                    winners == 1 && losers409 + unknown == hotUsers - 1,
                    "winners=" + winners + " seat_taken=" + losers409 + " of " + hotUsers
                            + (unknown > 0 ? " client-errors=" + unknown : ""));
        }
        check(sec, "storm outcomes all documented", onlyAllowed(t, allowedPlusClientErrors(t, RESERVE_OUTCOMES)),
                "buckets=" + new TreeSet<>(t.buckets.keySet()));
    }

    // ---------------------------------------------------------------- scenario b: general stampede

    /** One planned request. Duplicates and key reuses are separate entries sharing user and key. */
    record Planned(String user, String key, List<String> seats) {}

    static List<Planned> planStampede(List<String> userIds, List<String> allSeats) {
        Random rnd = new Random(seed);
        int nBase = stampede * 70 / 100, nDup = stampede * 15 / 100, nReuse = stampede - nBase - nDup;
        List<Planned> base = new ArrayList<>(), plan = new ArrayList<>();
        for (int i = 0; i < nBase; i++) {
            base.add(new Planned(userIds.get(rnd.nextInt(userIds.size())), "st-" + RUN + "-" + i, pickSeats(rnd, allSeats, null)));
        }
        plan.addAll(base);
        for (int i = 0; i < nDup; i++) plan.add(base.get(rnd.nextInt(base.size()))); // exact retry, same key+seats
        for (int i = 0; i < nReuse; i++) {                                           // same key, different seats
            Planned b = base.get(rnd.nextInt(base.size()));
            plan.add(new Planned(b.user(), b.key(), pickSeats(rnd, allSeats, b.seats())));
        }
        Collections.shuffle(plan, rnd);
        return plan;
    }

    /** 1-3 distinct random seats (50/30/20%); when `differentFrom` is given the result is a different set. */
    static List<String> pickSeats(Random rnd, List<String> all, List<String> differentFrom) {
        while (true) {
            int roll = rnd.nextInt(10), n = roll < 5 ? 1 : roll < 8 ? 2 : 3;
            Set<String> s = new TreeSet<>();
            while (s.size() < n) s.add(all.get(rnd.nextInt(all.size())));
            List<String> list = new ArrayList<>(s);
            if (differentFrom == null || !new TreeSet<>(differentFrom).equals(s)) return list;
        }
    }

    static void generalStampede() {
        String sec = "b";
        header("(b) GENERAL STAMPEDE: " + stampede + " requests, " + users + " users, " + seats
                + " seats, max " + maxInflight + " in flight");
        List<String> userIds = labels("st-" + RUN + "-u", users);
        prefetchTokens(userIds);
        List<String> allSeats = labels("S", seats);
        Ledger show = createShow("stampede", allSeats, 4);
        Tally t = new Tally("stampede");
        List<Planned> plan = planStampede(userIds, allSeats);

        long t0 = System.nanoTime();
        runBounded(plan.stream().<Callable<Void>>map(p -> () -> {
            reserve(show, t, p.user(), p.key(), p.seats(), null);
            return null;
        }).toList(), maxInflight);
        long wall = System.nanoTime() - t0;
        t.print(wall);

        check(sec, "stampede outcomes all documented (no 5xx / unexpected 4xx)",
                onlyAllowed(t, allowedPlusClientErrors(t, RESERVE_OUTCOMES)), "5xx=" + t.fiveXx());
        check(sec, "no seat sold twice (confirmed seats <= total)", show.expectedConfirmedSeats() <= seats,
                "client-seen confirmed seats=" + show.expectedConfirmedSeats() + " of " + seats);
        long multi = KEY_RESERVATIONS.values().stream().filter(s -> s.size() > 1).count();
        check(sec, "every idempotency key maps to at most one reservation", multi == 0,
                "keys with >1 reservation=" + multi + " of " + KEY_RESERVATIONS.size());
    }

    // ---------------------------------------------------------------- scenario c: idempotency

    static void idempotency() {
        String sec = "c";
        header("(c) IDEMPOTENCY: same key in parallel, then same key with different seats");
        String user = "idem-" + RUN;
        prefetchTokens(List.of(user));
        Ledger show = createShow("idem", labels("I", 10), 4);
        Tally t = new Tally("idem");
        int n = 50, m = 20;
        String key = "idem-key-" + RUN;

        long t0 = System.nanoTime();
        List<Resp> same = runSimultaneously(IntStreamOf(n, () -> reserve(show, t, user, key, List.of("I1"), null)));
        long created = same.stream().filter(r -> r.status == 201).count();
        long replays = same.stream().filter(r -> r.status == 200).count();
        Set<String> ids = same.stream().filter(r -> r.status == 200 || r.status == 201)
                .map(r -> r.field("reservation_id")).collect(Collectors.toSet());

        List<Resp> reuse = runSimultaneously(IntStreamOf(m, () -> reserve(show, t, user, key, List.of("I2"), null)));
        long conflicts = reuse.stream().filter(r -> r.status == 409 && "idempotency_key_reused".equals(r.field("reason"))).count();
        t.print(System.nanoTime() - t0);

        long unknown = same.stream().filter(r -> !r.ok()).count() + reuse.stream().filter(r -> !r.ok()).count();
        check(sec, n + " parallel same-key requests: exactly one 201, rest 200 replay",
                created == 1 && replays == n - 1, "201=" + created + " 200=" + replays);
        check(sec, "all replies carry the same reservation_id", ids.size() == 1, "distinct ids=" + ids.size());
        check(sec, m + " same-key/different-seats requests: all 409 idempotency_key_reused", conflicts == m,
                "409 idempotency_key_reused=" + conflicts + (unknown > 0 ? " client-errors=" + unknown : ""));
        check(sec, "exactly one seat booked (I2 never taken)", show.expectedConfirmedSeats() == 1,
                "confirmed seats=" + show.expectedConfirmedSeats());
    }

    /** Tiny helper: n copies of one task as a task list. */
    static <T> List<Callable<T>> IntStreamOf(int n, Callable<T> task) {
        List<Callable<T>> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(task);
        return l;
    }

    // ---------------------------------------------------------------- scenario d: per-user limit

    static void perUserLimit() {
        String sec = "d";
        header("(d) PER-USER LIMIT: one user, 10 parallel single-seat reserves, limit 4");
        String user = "limit-" + RUN;
        prefetchTokens(List.of(user));
        Ledger show = createShow("limit", labels("L", 20), 4);
        Tally t = new Tally("limit");

        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            int seat = i;
            tasks.add(() -> reserve(show, t, user, "limit-key-" + RUN + "-" + seat, List.of("L" + seat), null));
        }
        long t0 = System.nanoTime();
        List<Resp> rs = runSimultaneously(tasks);
        t.print(System.nanoTime() - t0);

        long ok = rs.stream().filter(r -> r.status == 201).count();
        long limited = rs.stream().filter(r -> r.status == 409 && "per_user_limit".equals(r.field("reason"))).count();
        check(sec, "exactly 4 succeed, 6 declined per_user_limit", ok == 4 && limited == 6,
                "201=" + ok + " per_user_limit=" + limited);
        check(sec, "show holds exactly 4 seats", show.expectedConfirmedSeats() == 4,
                "confirmed seats=" + show.expectedConfirmedSeats());
    }

    // ---------------------------------------------------------------- scenario e: identity

    static void identity() {
        String sec = "e";
        header("(e) IDENTITY: spoofed user_id, foreign cancel, owner cancel, double cancel, rebook");
        String alice = "alice-" + RUN, bob = "bob-" + RUN;
        prefetchTokens(List.of(alice, bob));
        Ledger show = createShow("identity", labels("E", 5), 4);
        Tally t = new Tally("identity");
        long t0 = System.nanoTime();

        Resp r1 = reserve(show, t, alice, "id-k1-" + RUN, List.of("E1"), "\"user_id\":\"mallory\"");
        check(sec, "spoofed body user_id ignored: reservation belongs to the token user",
                r1.status == 201 && alice.equals(r1.field("user_id")),
                "status=" + r1.status + " user_id=" + r1.field("user_id"));
        String resId = r1.field("reservation_id");

        Resp noAuth = send(req("/shows/" + show.showId + "/reserve").header("Content-Type", "application/json")
                .header("Idempotency-Key", "id-noauth-" + RUN)
                .POST(HttpRequest.BodyPublishers.ofString("{\"seats\":[\"E2\"]}")).build());
        check(sec, "reserve without a token -> 401", noAuth.status == 401, "status=" + noAuth.status);

        Resp foreign = cancel(show, t, resId, bob);
        check(sec, "another user cancelling it -> 404", foreign.status == 404, "status=" + foreign.status);
        Resp missing = cancel(show, t, UUID.randomUUID().toString(), bob);
        check(sec, "cancelling an unknown reservation -> 404", missing.status == 404, "status=" + missing.status);

        Resp own = cancel(show, t, resId, alice);
        check(sec, "owner cancel -> 200 cancelled", own.status == 200 && "cancelled".equals(own.field("status")),
                "status=" + own.status + " body.status=" + own.field("status"));
        Resp again = cancel(show, t, resId, alice);
        check(sec, "second cancel -> 409 already_cancelled",
                again.status == 409 && "already_cancelled".equals(again.field("reason")),
                "status=" + again.status + " reason=" + again.field("reason"));
        Resp rebook = reserve(show, t, alice, "id-k2-" + RUN, List.of("E1"), null);
        check(sec, "rebook the freed seat -> 201", rebook.status == 201, "status=" + rebook.status);

        t.print(System.nanoTime() - t0);
        check(sec, "identity outcomes all documented", t.fiveXx() == 0 && t.clientErrors() == 0,
                "5xx=" + t.fiveXx() + " client-errors=" + t.clientErrors());
    }

    // ---------------------------------------------------------------- scenario f: reconciliation

    static final Pattern COUNT = Pattern.compile("\"counts\"\\s*:\\s*\\{[^}]*\\}");

    static int countField(String counts, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d+)").matcher(counts);
        if (!m.find()) throw new IllegalStateException("no " + name + " in " + counts);
        return Integer.parseInt(m.group(1));
    }

    /** Parse /actuator/prometheus into series -> value (labels kept inside the key). Null if unavailable. */
    static Map<String, Double> scrape() {
        Resp r = send(req("/actuator/prometheus").GET().build());
        if (r.status != 200 || r.body == null || !r.body.contains("reservations_confirmed_total")) return null;
        Map<String, Double> m = new HashMap<>();
        for (String line : r.body.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            int sp = line.lastIndexOf(' ');
            try { m.put(line.substring(0, sp), Double.parseDouble(line.substring(sp + 1))); }
            catch (RuntimeException ignored) { }
        }
        return m;
    }

    static double delta(Map<String, Double> before, Map<String, Double> after, String series) {
        return after.getOrDefault(series, 0.0) - before.getOrDefault(series, 0.0);
    }

    static void reconcile(Map<String, Double> before) {
        String sec = "f";
        header("(f) RECONCILIATION: GET /shows/{id} per show, then Prometheus counter deltas");
        long showSlack = 0;
        for (Ledger l : LEDGERS) {
            Resp r = send(req("/shows/" + l.showId).GET().build());
            if (r.status != 200) { check(sec, "GET show " + l.name, false, describe(r)); continue; }
            Matcher cm = COUNT.matcher(r.body);
            if (!cm.find()) { check(sec, "GET show " + l.name, false, "no counts in body"); continue; }
            String counts = cm.group();
            int total = countField(counts, "total"), avail = countField(counts, "available"),
                    held = countField(counts, "held"), conf = countField(counts, "confirmed");
            int perSeatConfirmed = r.body.split("\"status\"\\s*:\\s*\"confirmed\"", -1).length - 1;
            long slack = l.unknown.sum();
            showSlack += slack;
            int expected = l.expectedConfirmedSeats();
            check(sec, "show " + l.name + ": available+held+confirmed == total",
                    avail + held + conf == total && total == l.totalSeats && held == 0 && perSeatConfirmed == conf,
                    "avail=" + avail + " held=" + held + " confirmed=" + conf + " total=" + total
                            + " per-seat confirmed=" + perSeatConfirmed);
            check(sec, "show " + l.name + ": confirmed == client tally (201 minus cancelled)",
                    Math.abs(conf - expected) <= slack,
                    "server=" + conf + " client=" + expected + (slack > 0 ? " slack=" + slack : ""));
        }

        sleep(1500); // seats{} gauge is cached ~1s server side
        Map<String, Double> after = scrape();
        if (before == null || after == null) {
            skipped(sec, "Prometheus counter reconciliation",
                    "/actuator/prometheus not reachable or has no reservations_* metrics");
            return;
        }
        long slack = ALL_RESERVES.clientErrors() + ALL_CANCELS.clientErrors();
        Tally rv = ALL_RESERVES, cn = ALL_CANCELS;
        long validation = rv.buckets.entrySet().stream()
                .filter(e -> e.getKey().equals("4xx-other 400") || e.getKey().equals("4xx-other 404"))
                .mapToLong(e -> e.getValue().sum()).sum();
        String[][] rows = {
                {"reservations_confirmed_total", String.valueOf(rv.count("201 confirmed"))},
                {"reservations_idempotent_replay_total", String.valueOf(rv.count("200 idempotent replay"))},
                {"reservations_declined_total{reason=\"seat_taken\"}", String.valueOf(rv.count("409 declined:seat_taken"))},
                {"reservations_declined_total{reason=\"per_user_limit\"}", String.valueOf(rv.count("409 declined:per_user_limit"))},
                {"reservations_declined_total{reason=\"idempotency_conflict\"}", String.valueOf(rv.count("409 declined:idempotency_key_reused"))},
                {"reservations_declined_total{reason=\"validation\"}", String.valueOf(validation)},
                {"reservations_cancelled_total", String.valueOf(cn.count("200 cancelled"))},
                {"cancels_declined_total{reason=\"already_cancelled\"}", String.valueOf(cn.count("409 declined:already_cancelled"))},
                {"cancels_declined_total{reason=\"not_found\"}", String.valueOf(cn.count("4xx-other 404"))},
                {"reservations_errors_total", "0"},
                {"cancels_errors_total", "0"},
        };
        for (String[] row : rows) {
            double d = delta(before, after, row[0]);
            long expected = Long.parseLong(row[1]);
            boolean errorsSeries = row[0].endsWith("errors_total");
            check(sec, "metric " + row[0] + " delta == client",
                    errorsSeries ? d == 0 : Math.abs(d - expected) <= slack,
                    "server delta=" + (long) d + " client=" + expected + (slack > 0 && !errorsSeries ? " slack=" + slack : ""));
        }
        double gaugeDelta = delta(before, after, "seats{status=\"confirmed\"}");
        long expectedSeats = LEDGERS.stream().mapToLong(Ledger::expectedConfirmedSeats).sum();
        check(sec, "gauge seats{status=\"confirmed\"} delta == client confirmed seats",
                Math.abs(gaugeDelta - expectedSeats) <= showSlack,
                "server delta=" + (long) gaugeDelta + " client=" + expectedSeats
                        + (showSlack > 0 ? " slack=" + showSlack : ""));
    }

    // ---------------------------------------------------------------- startup, summary, main

    /** Cold-start tolerance: poll readiness until 200 (up to 120s). */
    static boolean waitForReadiness() {
        long start = System.nanoTime();
        System.out.println("Waiting for " + baseUrl + "/actuator/health/readiness (up to 120s) ...");
        int polls = 0;
        while (System.nanoTime() - start < 120_000_000_000L) {
            polls++;
            try {
                HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health/readiness"))
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    System.out.printf("Ready after %.1fs (%d polls)%n", (System.nanoTime() - start) / 1e9, polls);
                    return true;
                }
            } catch (IOException e) {
                // not up yet (refused / timed out / waking from sleep): keep polling
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            sleep(1000);
        }
        return false;
    }

    static int summarize(long runNanos) {
        header("SUMMARY");
        System.out.printf("  total HTTP calls=%d  wall=%.1fs  connect-retries=%d  5xx=%d  client-errors=%d%n",
                httpCalls.sum(), runNanos / 1e9, connectRetries.sum(), serverErrors.sum(), clientErrorCalls.sum());
        if (!clientErrorKinds.isEmpty()) System.out.println("  client-error kinds: " + clientErrorKinds);
        System.out.println("  all reserve calls:");
        ALL_RESERVES.print(runNanos);

        // run-level checks
        long calls = Math.max(1, httpCalls.sum());
        check("run", "zero 5xx responses on any call", serverErrors.sum() == 0,
                "5xx=" + serverErrors.sum() + (fiveXxSamples.isEmpty() ? "" : " e.g. " + fiveXxSamples));
        if (clientErrorCalls.sum() > 0) {
            warn("run", "client-side errors (not server 5xx)", clientErrorCalls.sum() + " of " + calls + " calls "
                    + clientErrorKinds + "; unknown-outcome slack applied in reconciliation");
        }
        if (connectRetries.sum() > 0) {
            warn("run", "connect failures were retried", connectRetries.sum() + " retries (OS backlog artifact)");
        }
        check("run", "client errors <= 1% of calls (checks stay meaningful)",
                clientErrorCalls.sum() * 100 <= calls, clientErrorCalls.sum() + " of " + calls);

        System.out.println();
        System.out.println("  RESULT TABLE");
        for (Check c : CHECKS) System.out.printf("  %-8s [%s] %s%n", c.status(), c.section(), c.name());
        long fails = CHECKS.stream().filter(c -> c.status().equals("FAIL")).count();
        long warns = CHECKS.stream().filter(c -> c.status().equals("WARN")).count();
        long skips = CHECKS.stream().filter(c -> c.status().equals("SKIPPED")).count();
        System.out.printf("%n  %d checks: %d pass, %d fail, %d warn, %d skipped  =>  %s%n", CHECKS.size(),
                CHECKS.size() - fails - warns - skips, fails, warns, skips, fails == 0 ? "PASS" : "FAIL");
        return fails == 0 ? 0 : 1;
    }

    public static void main(String[] args) {
        parseArgs(args);
        System.out.printf("Burst run %s against %s  (seats=%d stampede=%d users=%d hot-users=%d hot-seats=%d max-inflight=%d)%n",
                RUN, baseUrl, seats, stampede, users, hotUsers, hotSeats, maxInflight);
        long start = System.nanoTime();
        int code;
        try {
            if (!waitForReadiness()) {
                System.err.println("FAIL: service not ready within 120s");
                System.exit(2);
            }
            Map<String, Double> before = scrape();
            if (before == null) System.out.println("note: /actuator/prometheus unavailable, metric checks will be SKIPPED");

            hotSeatStorm();
            generalStampede();
            idempotency();
            perUserLimit();
            identity();
            reconcile(before);
            code = summarize(System.nanoTime() - start);
        } catch (Throwable e) {
            System.err.println("ABORTED: " + e);
            e.printStackTrace();
            code = 2;
        }
        System.exit(code);
    }
}
