package sss.quartz.jobs;

import static org.quartz.TriggerBuilder.newTrigger;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A wrapper for another wrappedJob that provides re-scheduling of attempts
 * after a failure. Quartz's default behavior is a little naive, it only allows
 * immediate retries and can't do delays without blocking a scheduler thread.
 * This implementation will create new triggers to reschedule the job for later.
 * <p>
 * The interface contract for {@link JobExecutionException} is redefined
 * slightly here - {@link JobExecutionException#refireImmediately()} throw by
 * the wrapped job is used to trigger rescheduling based <code>retryDelay</code>
 * instead of rescheduling immediately.
 * <p>
 * The following {@link JobDataMap} keys are reserved for the wrapper and are
 * merged into the rescheduled job's map along with the original triggers merged
 * data map.
 * <dl>
 * <dt>_retryWrapper.retryCount</dt>
 * <dd>number of current retry. if this is zero or null, then it's assumed this
 * is the original trigger for the job</dd>
 * </dl>
 * 
 * @author jsteele
 */
public class RetryJobWrapper implements Job {
	public static final String WRAPPED_JOB_KEY = "wrappedJob";
	public static final String MAX_RETRIES_KEY = "maxRetries";
	public static final String RETRY_DELAY_KEY = "retryDelay";
	public static final String RETRY_COUNT_KEY = "retryJobWrapper.retryCount";
	public static final String ORIG_SCHED_TIME_KEY = "retryJobWrapper.origSchedTime";

	/** Class logger. */
	private static final Logger log = LoggerFactory.getLogger(RetryJobWrapper.class);

	/** Job being wrapped for retry. */
	private Job wrappedJob;

	@Override
	public void execute(JobExecutionContext context) throws JobExecutionException {
		final Integer maxRetries = context.getMergedJobDataMap().getIntegerFromString(MAX_RETRIES_KEY);
		if (null == maxRetries) {
			throw new IllegalArgumentException(MAX_RETRIES_KEY + " is a mandatory wrappedJob detail");
		}
		final Integer retryDelay = context.getMergedJobDataMap().getIntegerFromString(RETRY_DELAY_KEY);
		if (null == retryDelay) {
			throw new IllegalArgumentException(RETRY_DELAY_KEY + " is a mandatory wrappedJob detail");
		}
		final String wrappedJobClassname = context.getMergedJobDataMap().getString(WRAPPED_JOB_KEY);
		if (null == wrappedJobClassname) {
			throw new IllegalArgumentException(WRAPPED_JOB_KEY + " is a mandatory wrappedJob detail");
		}
		final int retryCount = context.getMergedJobDataMap().containsKey(RETRY_COUNT_KEY) ? context
				.getMergedJobDataMap().getInt(RETRY_COUNT_KEY) : 0;

		final SimpleDateFormat df = new SimpleDateFormat();

		if (retryCount > 0) {
			final Date origTime = context.getMergedJobDataMap().containsKey(ORIG_SCHED_TIME_KEY) ? new Date(
					context.getMergedJobDataMap().getLong(ORIG_SCHED_TIME_KEY)) : context
					.getScheduledFireTime();

			log.info(String.format(
					"RetryJobWrapper<%s> - executing (retry %d of %d) originally scheduled for %s: %s",
					wrappedJobClassname, retryCount, maxRetries, df.format(origTime), context));
		} else {
			log.info(String.format("RetryJobWrapper<%s> - executing: %s", wrappedJobClassname, context));
		}

		executeWithRetries(context, getWrappedJob(wrappedJobClassname), maxRetries, retryDelay,
				retryCount);
	}

	/**
	 * Executes the wrapped job with exception handling to look for
	 * {@link JobExecutionException#setRefireImmediately(boolean)} =
	 * <code>true</code> and schedule another attempt.
	 * 
	 * @param context
	 *          job execution context
	 * @param wrappedJob
	 *          job to execute
	 * @param maxRetries
	 *          maximum number of retries
	 * @param retryDelay
	 *          delay, in seconds before re-attempting the job
	 * @param retryCount
	 *          current retry count
	 * @throws JobExecutionException
	 */
	private void executeWithRetries(JobExecutionContext context, final Job wrappedJob,
			final int maxRetries, final int retryDelay, final int retryCount)
			throws JobExecutionException {
		try {
			wrappedJob.execute(context);
			if (retryCount > 0) {
				log.warn(String.format("wrapped job %s succeeded after %d retries", wrappedJob.getClass()
						.getName(), retryCount));
			}
		} catch (JobExecutionException jee) {
			if (jee.refireImmediately()) {
				if (retryCount < maxRetries) {
					log.warn(String.format("wrapped job %s requested refiring - rescheduling attempt: %s",
							wrappedJob.getClass().getName(), jee), jee);
					try {
						rescheduleJob(context, retryCount + 1, retryDelay);
					} catch (SchedulerException se) {
						log.warn(String.format("wrapped job %s could not be rescheduled: %s", wrappedJob
								.getClass().getName(), jee), jee);
						throw new JobExecutionException("wrapped job " + wrappedJob.getClass().getName()
								+ " could not be rescheduled: " + se, se);
					}
				} else {
					// Re-throwing to set retryImmedaitely to false
					throw new JobExecutionException(String.format(
							"wrapped job %s requested refiring but has exhausted rescheduling attempts: %s",
							wrappedJob.getClass().getName(), jee), jee);
				}
			} else {
				log.info(String.format(
						"wrapped job %s did not request refiring - will not reschedule attempt: %s", wrappedJob
								.getClass().getName(), jee), jee);
				throw jee;
			}
		}
	}

	/**
	 * Reschedules the current job for execution.
	 * 
	 * @param context
	 *          job execution context
	 * @param retryCount
	 *          retry count being requested (>= 1)
	 * @param retryDelay
	 *          delay, in seconds, before retrying the job
	 * @throws SchedulerException
	 *           if there was a problem rescheduling the job
	 */
	private void rescheduleJob(JobExecutionContext context, int retryCount, int retryDelay)
			throws SchedulerException {
		final Scheduler scheduler = context.getScheduler();
		final Calendar retryTime = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
		retryTime.add(Calendar.SECOND, retryDelay);

		final Date origTime = context.getMergedJobDataMap().containsKey(ORIG_SCHED_TIME_KEY) ? new Date(
				context.getMergedJobDataMap().getLong(ORIG_SCHED_TIME_KEY)) : context
				.getScheduledFireTime();

		// @formatter:off
		final TriggerBuilder<Trigger> builder = newTrigger()
				.withDescription("retry #" + retryCount)
				.withPriority(context.getTrigger().getPriority())
				.startAt(retryTime.getTime())
				.forJob(context.getJobDetail().getKey());
		// @formatter:on

		if (null != context.getTrigger().getJobDataMap()) {
			builder.usingJobData(new JobDataMap(context.getTrigger().getJobDataMap()));
		}
		builder.usingJobData(RETRY_COUNT_KEY, Integer.toString(retryCount));
		builder.usingJobData(ORIG_SCHED_TIME_KEY, Long.toString(origTime.getTime()));

		scheduler.scheduleJob(builder.build());
	}

	/**
	 * Lazily instantiates the wrapped wrappedJob implementation class.
	 * 
	 * @param wrappedJobClassname
	 *          fully qualified classname for the wrapped wrappedJob
	 * @return instance of the wrapped wrappedJob
	 */
	@SuppressWarnings("unchecked")
	private synchronized Job getWrappedJob(final String wrappedJobClassname) {
		if (null == wrappedJob) {
			try {
				final Class<? extends Job> wrappedJobClass = (Class<? extends Job>) Class
						.forName(wrappedJobClassname);
				wrappedJob = wrappedJobClass.newInstance();
			} catch (ClassNotFoundException cnfe) {
				throw new IllegalArgumentException("wrappedJob (" + wrappedJobClassname
						+ ") not found on classpath");
			} catch (InstantiationException | IllegalAccessException e) {
				throw new IllegalArgumentException("wrappedJob (" + wrappedJobClassname
						+ ") could not be instantiated: " + e, e);
			}
		}
		return wrappedJob;
	}
}
