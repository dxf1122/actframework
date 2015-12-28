package act.job;

import act.app.App;
import act.app.event.AppEventId;
import act.conf.AppConfig;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.osgl.$;
import org.osgl.exception.NotAppliedException;
import org.osgl.logging.L;
import org.osgl.logging.Logger;
import org.osgl.util.E;
import org.osgl.util.S;
import org.rythmengine.utils.Time;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static act.app.event.AppEventId.START;
import static act.app.event.AppEventId.STOP;
import static act.job.AppJobManager.appEventJobId;

abstract class JobTrigger {

    protected static Logger logger = L.get(App.class);

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    final void register(_Job job, AppJobManager manager) {
        job.trigger(this);
        manager.addJob(job);
        schedule(manager, job);
    }

    void scheduleFollowingCalls(AppJobManager manager, _Job job) {}

    void schedule(AppJobManager manager, _Job job) {}

    static JobTrigger of(AppConfig config, Cron anno) {
        String v = anno.value();
        if (v.startsWith("cron.")) {
            v = (String) config.get(v);
        } else if (v.startsWith("${") && v.endsWith("}")) {
            v = v.substring(2, v.length() - 1);
            v = (String) config.get(v);
        }
        if (S.blank(v)) {
            throw E.invalidConfiguration("Cannot find configuration for cron: %s", anno.value());
        }
        return cron(v);
    }

    static JobTrigger of(AppConfig config, OnAppStart anno) {
        if (anno.async()) {
            return alongWith(START);
        } else {
            return after(START);
        }
    }

    static JobTrigger of(AppConfig config, OnAppStop anno) {
        if (anno.async()) {
            return alongWith(STOP);
        } else {
            return before(STOP);
        }
    }

    static JobTrigger of(AppConfig config, FixedDelay anno) {
        String delay = anno.value();
        if (delay.startsWith("delay.")) {
            delay = (String) config.get(delay);
        } else if (delay.startsWith("${") && delay.endsWith("}")) {
            delay = delay.substring(2, delay.length() - 1);
            delay = (String) config.get(delay);
        }
        if (S.blank(delay)) {
            throw E.invalidConfiguration("Cannot find configuration for delay: %s", anno.value());
        }
        return fixedDelay(delay);
    }

    static JobTrigger of(AppConfig config, Every anno) {
        String duration = anno.value();
        if (duration.startsWith("every.")) {
            duration = (String) config.get(duration);
        } else if (duration.startsWith("${") && duration.endsWith("}")) {
            duration = duration.substring(2, duration.length() - 1);
            duration = (String) config.get(duration);
        }
        if (S.blank(duration)) {
            throw E.invalidConfiguration("Cannot find configuration for duration: %s", anno.value());
        }
        return every(duration);
    }

    static JobTrigger of(AppConfig config, AlongWith anno) {
        String id = anno.value();
        E.illegalArgumentIf(S.blank(id), "associate job ID cannot be empty");
        return new _AlongWith(id);
    }

    static JobTrigger of(AppConfig config, InvokeAfter anno) {
        String id = anno.value();
        E.illegalArgumentIf(S.blank(id), "associate job ID cannot be empty");
        return new _After(id);
    }

    static JobTrigger of(AppConfig config, InvokeBefore anno) {
        String id = anno.value();
        E.illegalArgumentIf(S.blank(id), "associate job ID cannot be empty");
        return new _Before(id);
    }

    static JobTrigger cron(String expression) {
        return new _Cron(expression);
    }

    static JobTrigger fixedDelay(String duration) {
        return new _FixedDelay(duration);
    }

    static JobTrigger every(String duration) {
        return new _Every(duration);
    }

    static JobTrigger onAppStart(boolean async) {
        return async ? alongWith(START) : after(START);
    }

    static JobTrigger onAppStop(boolean async) {
        return async ? alongWith(STOP) : before(STOP);
    }

    static JobTrigger onAppEvent(AppEventId eventId, boolean async) {
        return async ? alongWith(eventId) : after(eventId);
    }

    static JobTrigger delayForSeconds(int seconds) {
        return new _FixedDelay(seconds);
    }

    static JobTrigger alongWith(String jobId) {
        return new _AlongWith(jobId);
    }

    static JobTrigger alongWith(AppEventId appEvent) {
        return new _AlongWith(appEventJobId(appEvent));
    }

    static JobTrigger before(String jobId) {
        return new _Before(jobId);
    }

    static JobTrigger before(AppEventId appEvent) {
        return before(appEventJobId(appEvent));
    }

    static JobTrigger after(String jobId) {
        return new _After(jobId);
    }

    static JobTrigger after(AppEventId appEvent) {
        return after(appEventJobId(appEvent));
    }

    static class _Cron extends JobTrigger {
        private static final CronDefinition def = CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ);
        private static final CronParser parser = new CronParser(def);
        private com.cronutils.model.Cron cron;
        _Cron(String expression) {
            cron = parser.parse(expression);
        }

        @Override
        public String toString() {
            return S.builder("cron :").append(cron.asString()).toString();
        }

        @Override
        void schedule(AppJobManager manager, _Job job) {
            DateTime now = DateTime.now();
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            Duration nextExecution = executionTime.timeToNextExecution(now.withDurationAdded(1, 1));
            long seconds = nextExecution.getStandardSeconds();
            int times = 1;
            while (seconds == 0) {
                nextExecution = executionTime.timeToNextExecution(now.withDurationAdded(1, times++));
                seconds = nextExecution.getStandardSeconds();
            }
            manager.executor().schedule(job, seconds, TimeUnit.SECONDS);
        }

        @Override
        void scheduleFollowingCalls(AppJobManager manager, _Job job) {
            schedule(manager, job);
        }
    }

    private abstract static class _Periodical extends JobTrigger {
        protected int seconds;
        _Periodical(String duration) {
            E.illegalArgumentIf(S.blank(duration), "delay duration shall not be empty");
            seconds = Time.parseDuration(duration);
            E.illegalArgumentIf(seconds < 1, "delay duration shall not be zero or negative number");
        }
        _Periodical(int seconds) {
            E.illegalArgumentIf(seconds < 1, "delay duration cannot be zero or negative");
            this.seconds = seconds;
        }
    }

    private static class _FixedDelay extends _Periodical {
        _FixedDelay(String duration) {
            super(duration);
        }
        _FixedDelay(int seconds) {
            super(seconds);
        }

        @Override
        public String toString() {
            return S.builder("fixed delay of ").append(seconds).append(" seconds").toString();
        }

        @Override
        void schedule(AppJobManager manager, _Job job) {
            ScheduledThreadPoolExecutor executor = manager.executor();
            executor.scheduleWithFixedDelay(job, seconds, seconds, TimeUnit.SECONDS);
        }
    }

    private static class _Every extends _Periodical {
        _Every(String duration) {
            super(duration);
        }
        _Every(int seconds) {
            super(seconds);
        }

        @Override
        public String toString() {
            return S.builder("every ").append(seconds).append(" seconds").toString();
        }

        @Override
        void schedule(AppJobManager manager, _Job job) {
            ScheduledThreadPoolExecutor executor = manager.executor();
            executor.scheduleAtFixedRate(job, seconds, seconds, TimeUnit.SECONDS);
        }
    }

    private abstract static class _AssociatedTo extends JobTrigger {
        protected String id;
        _AssociatedTo(String id) {
            E.illegalArgumentIf(S.blank(id), "associate job ID expected");
            this.id = id;
        }

        @Override
        void schedule(AppJobManager manager, _Job job) {
            if (null == id) {
                logger.warn("Failed to register job because target job not found: %s. Will try again after app started", id);
                scheduleDelayedRegister(manager, job);
            } else {
                _Job associateTarget = manager.jobById(id);
                associate(job, associateTarget);
            }
        }

        private void scheduleDelayedRegister(final AppJobManager manager, final _Job job) {
            final String id = delayedRegisterJobId(job);
            before(START).register(new _Job(id, manager, new $.F0<Void>() {
                @Override
                public Void apply() throws NotAppliedException, $.Break {
                    _Job associateTo = manager.jobById(id);
                    if (null == id) {
                        throw E.invalidConfiguration("dependency job not found: %s", id);
                    }
                    associate(job, associateTo);
                    return null;
                }
            }), manager);
        }

        private String delayedRegisterJobId(_Job job) {
            StringBuilder sb = S.builder("delayed_association_register-").append(job.id()).append("-to-").append(id);
            return sb.toString();
        }

        abstract void associate(_Job theJob, _Job toJob);
    }

    private static class _AlongWith extends _AssociatedTo {
        _AlongWith(String id) {
            super(id);
        }

        @Override
        public String toString() {
            return S.builder("along with ").append(id).toString();
        }

        @Override
        void associate(_Job theJob, _Job toJob) {
            toJob.addParallelJob(theJob);
        }
    }

    private static class _Before extends _AssociatedTo {
        _Before(String id) {
            super(id);
        }

        @Override
        public String toString() {
            return S.builder("before ").append(id).toString();
        }

        @Override
        void associate(_Job theJob, _Job toJob) {
            toJob.addPrecedenceJob(theJob);
        }
    }

    private static class _After extends _AssociatedTo {
        _After(String id) {
            super(id);
        }

        @Override
        public String toString() {
            return S.builder("after ").append(id).toString();
        }

        @Override
        void associate(_Job theJob, _Job toJob) {
            toJob.addFollowingJob(theJob);
        }
    }

}



