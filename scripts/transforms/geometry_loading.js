/* Bounded geometry work; priorities are re-evaluated as camera/feed state changes. */
function createGeometryQueue(load, priority, limit) {
    var jobs = new Map();
    var failures = new Map();
    var active = 0;
    var timer = null;
    var stopped = false;

    function schedule() {
        if (!timer && !stopped && jobs.size) timer = setTimeout(pump, 100);
    }
    function pump() {
        clearTimeout(timer);
        timer = null;
        if (stopped) return;
        var candidates = [];
        jobs.forEach(function (job) {
            if (job.started) return;
            var best = Infinity;
            job.owners.forEach(function (owner) {
                var value = priority(owner);
                if (value === null) job.owners.delete(owner);
                else best = Math.min(best, value);
            });
            if (!job.owners.size) {
                jobs.delete(job.url);
                job.reject(new Error("Geometry demand removed"));
            } else if (Number.isFinite(best)) candidates.push({job: job, priority: best});
        });
        candidates.sort(function (a, b) { return a.priority - b.priority; });
        while (active < limit && candidates.length) start(candidates.shift().job);
        schedule();
    }
    function start(job) {
        active++;
        job.started = true;
        Promise.resolve().then(function () { return load(job.url, job.controller.signal); })
            .then(function (value) {
                failures.delete(job.url);
                job.resolve(value);
            }, function (error) {
                var previous = failures.get(job.url);
                var delay = Math.min(previous ? previous.delay * 2 : 1000, 15000);
                failures.set(job.url, {delay: delay, until: Date.now() + delay});
                job.reject(error);
            }).finally(function () {
                jobs.delete(job.url);
                active--;
                pump();
            });
    }
    return {
        load: function (url, owner) {
            if (stopped) return Promise.reject(new Error("Geometry queue disposed"));
            var job = jobs.get(url);
            if (!job) {
                var failure = failures.get(url);
                if (failure && Date.now() < failure.until) {
                    return Promise.reject(new Error("Geometry retry cooling down"));
                }
                job = {url: url, owners: new Set(), controller: new AbortController()};
                job.promise = new Promise(function (resolve, reject) {
                    job.resolve = resolve;
                    job.reject = reject;
                });
                jobs.set(url, job);
            }
            job.owners.add(owner);
            schedule();
            return job.promise;
        },
        addOwner: function (url, owner) {
            var job = jobs.get(url);
            if (job) job.owners.add(owner);
        },
        dispose: function () {
            stopped = true;
            clearTimeout(timer);
            jobs.forEach(function (job) {
                job.controller.abort();
                job.reject(new Error("Geometry queue disposed"));
            });
            jobs.clear();
            failures.clear();
        }
    };
}
