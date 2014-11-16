package com.twotoasters.watchface.gears.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.text.format.DateFormat;
import android.view.ViewDebug.ExportedProperty;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.TimeZone;

public class Watch {

    private static final LocalTime TEST_TIME = LocalTime.parse("01:02:03");

    private static final String ACTION_KEEP_WATCHFACE_AWAKE = "intent.action.keep.watchface.awake";

    /**
     * The default formatting pattern in 12-hour mode. This pattern is used
     * if {@link #setFormat12Hour(CharSequence)} is called with a null pattern
     * or if no pattern was specified when creating an instance of this class.
     *
     * This default pattern shows only the time, hours and minutes, and an am/pm
     * indicator.
     *
     * @see #setFormat12Hour(CharSequence)
     * @see #getFormat12Hour()
     */
    public static final DateTimeFormatter DEFAULT_FORMAT_12_HOUR =
            new DateTimeFormatterBuilder().appendHourOfHalfday(1)
                    .appendLiteral(':').appendMinuteOfHour(2)
                    .appendLiteral(':').appendSecondOfMinute(2)
                    .appendLiteral(' ').appendHalfdayOfDayText()
                    .toFormatter();

    /**
     * The default formatting pattern in 24-hour mode. This pattern is used
     * if {@link #setFormat24Hour(CharSequence)} is called with a null pattern
     * or if no pattern was specified when creating an instance of this class.
     *
     * This default pattern shows only the time, hours and minutes.
     *
     * @see #setFormat24Hour(CharSequence)
     * @see #getFormat24Hour()
     */
    public static final DateTimeFormatter DEFAULT_FORMAT_24_HOUR =
            new DateTimeFormatterBuilder().appendHourOfDay(1)
                    .appendLiteral(':').appendMinuteOfHour(2)
                    .appendLiteral(':').appendSecondOfMinute(2)
                    .toFormatter();


    private DateTimeFormatter mFormat12;
    private DateTimeFormatter mFormat24;

    @ExportedProperty
    private DateTimeFormatter mFormat;

    @ExportedProperty
    private boolean mHasSeconds;

    private boolean mAttached;

    private String mTimeZone;

    private AlarmManager alarmManager;

    private final ContentObserver mFormatChangeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            chooseFormat();
            onTimeChanged();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            chooseFormat();
            onTimeChanged();
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mTimeZone == null && Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
                mTimeZone = intent.getStringExtra("time-zone");
            }

            if (!ACTION_KEEP_WATCHFACE_AWAKE.equals(intent.getAction())) {
                onTimeChanged();
            }
        }
    };

    private BroadcastReceiver mBatInfoReceiver = new BroadcastReceiver(){
        @Override
        public void onReceive(Context arg0, Intent intent) {
            onBatteryLevelChanged(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0));
        }
    };

    private final Runnable mTicker = new Runnable() {
        public void run() {
            onTimeChanged();

            long now = SystemClock.uptimeMillis();
            long next = now + (1000 - now % 1000);

            if (hasWatchface())
                getWatchface().getHandler().postAtTime(mTicker, next);
        }
    };

    private WeakReference<IWatchface> watchfaceRef;

    public Watch(IWatchface watchface) {
        if (watchface == null) {
            throw new AssertionError("Watchface can not be null");
        }

        watchfaceRef = new WeakReference<>(watchface);
        init();
    }


    private void init() {
        if (mFormat12 == null || mFormat24 == null) {
            Locale locale = getWatchface().getContext().getResources().getConfiguration().locale;
            if (mFormat12 == null) {
                mFormat12 = DEFAULT_FORMAT_12_HOUR.withLocale(locale);
            }
            if (mFormat24 == null) {
                mFormat24 = DEFAULT_FORMAT_24_HOUR.withLocale(locale);
            }
        }

        initAlarmManager();

        // Wait until onAttachedToWindow() to handle the ticker
        chooseFormat(false);
    }

    private void initAlarmManager() {
        if(alarmManager == null && notInEditMode()) {
            alarmManager = (AlarmManager) getWatchface().getContext().getSystemService(Context.ALARM_SERVICE);
        }
    }

    private boolean notInEditMode() {
        try {
            return !getWatchface().isInEditMode();
        } catch(NullPointerException npe) {
            return true;
        }
    }

    /**
     * Returns the formatting pattern used to display the date and/or time
     * in 12-hour mode. The formatting pattern syntax is described in
     * {@link android.text.format.DateFormat}.
     *
     * @return A {@link CharSequence} or null.
     *
     * @see #setFormat12Hour(CharSequence)
     * @see #is24HourModeEnabled()
     */
    @ExportedProperty
    @NonNull
    public DateTimeFormatter getFormat12Hour() {
        return mFormat12;
    }

    /**
     * <p>Specifies the formatting pattern used to display the date and/or time
     * in 12-hour mode. The formatting pattern syntax is described in
     * {@link android.text.format.DateFormat}.</p>
     *
     * <p>If this pattern is set to null, {@link #getFormat24Hour()} will be used
     * even in 12-hour mode. If both 24-hour and 12-hour formatting patterns
     * are set to null, the default pattern for the current locale will be used
     * instead.</p>
     *
     * <p><strong>Note:</strong> if styling is not needed, it is highly recommended
     * you supply a format string generated by
     * {@link android.text.format.DateFormat#getBestDateTimePattern(java.util.Locale, String)}. This method
     * takes care of generating a format string adapted to the desired locale.</p>
     *
     *
     * @param format A date/time formatting pattern as described in {@link android.text.format.DateFormat}
     *
     * @see #getFormat12Hour()
     * @see #is24HourModeEnabled()
     * @see android.text.format.DateFormat#getBestDateTimePattern(java.util.Locale, String)
     * @see android.text.format.DateFormat
     *
     * @attr ref android.R.styleable#TextClock_format12Hour
     */
    public void setFormat12Hour(@NonNull CharSequence format) {
        mFormat12 = DateTimeFormat.forPattern(format.toString());

        chooseFormat();
        onTimeChanged();
    }

    /**
     * Returns the formatting pattern used to display the date and/or time
     * in 24-hour mode. The formatting pattern syntax is described in
     * {@link android.text.format.DateFormat}.
     *
     * @return A {@link CharSequence} or null.
     *
     * @see #setFormat24Hour(CharSequence)
     * @see #is24HourModeEnabled()
     */
    @ExportedProperty
    @NonNull
    public DateTimeFormatter getFormat24Hour() {
        return mFormat24;
    }

    /**
     * <p>Specifies the formatting pattern used to display the date and/or time
     * in 24-hour mode. The formatting pattern syntax is described in
     * {@link android.text.format.DateFormat}.</p>
     *
     * <p>If this pattern is set to null, {@link #getFormat24Hour()} will be used
     * even in 12-hour mode. If both 24-hour and 12-hour formatting patterns
     * are set to null, the default pattern for the current locale will be used
     * instead.</p>
     *
     * <p><strong>Note:</strong> if styling is not needed, it is highly recommended
     * you supply a format string generated by
     * {@link android.text.format.DateFormat#getBestDateTimePattern(java.util.Locale, String)}. This method
     * takes care of generating a format string adapted to the desired locale.</p>
     *
     * @param format A date/time formatting pattern as described in {@link android.text.format.DateFormat}
     *
     * @see #getFormat24Hour()
     * @see #is24HourModeEnabled()
     * @see android.text.format.DateFormat#getBestDateTimePattern(java.util.Locale, String)
     * @see android.text.format.DateFormat
     *
     * @attr ref android.R.styleable#TextClock_format24Hour
     */
    public void setFormat24Hour(@NonNull CharSequence format) {
        mFormat24 = DateTimeFormat.forPattern(format.toString());

        chooseFormat();
        onTimeChanged();
    }

    /**
     * Indicates whether the system is currently using the 24-hour mode.
     *
     * When the system is in 24-hour mode, this view will use the pattern
     * returned by {@link #getFormat24Hour()}. In 12-hour mode, the pattern
     * returned by {@link #getFormat12Hour()} is used instead.
     *
     * If either one of the formats is null, the other format is used. If
     * both formats are null, the default formats for the current locale are used.
     *
     * @return true if time should be displayed in 24-hour format, false if it
     *         should be displayed in 12-hour format.
     *
     * @see #setFormat12Hour(CharSequence)
     * @see #getFormat12Hour()
     * @see #setFormat24Hour(CharSequence)
     * @see #getFormat24Hour()
     */
    public boolean is24HourModeEnabled() {
        return hasWatchface() && DateFormat.is24HourFormat(getWatchface().getContext());
    }

    @SuppressWarnings("unused")
    public DateTime getTime() {
        if(mTimeZone == null) {
            return DateTime.now();
        } else {
            return DateTime.now(DateTimeZone.forID(mTimeZone));
        }
    }

    /**
     * Indicates which time zone is currently used by this view.
     *
     * @return The ID of the current time zone or null if the default time zone,
     *         as set by the user, must be used
     *
     * @see TimeZone
     * @see java.util.TimeZone#getAvailableIDs()
     * @see #setTimeZone(String)
     */
    @NonNull
    public String getTimeZone() {
        return mTimeZone;
    }

    /**
     * Sets the specified time zone to use in this clock. When the time zone
     * is set through this method, system time zone changes (when the user
     * sets the time zone in settings for instance) will be ignored.
     *
     * @param timeZone The desired time zone's ID as specified in {@link TimeZone}
     *                 or null to user the time zone specified by the user
     *                 (system time zone)
     *
     * @see #getTimeZone()
     * @see java.util.TimeZone#getAvailableIDs()
     * @see TimeZone#getTimeZone(String)
     *
     * @attr ref android.R.styleable#TextClock_timeZone
     */
    public void setTimeZone(@NonNull String timeZone) {
        mTimeZone = timeZone;

        onTimeChanged();
    }

    /**
     * Selects either one of {@link #getFormat12Hour()} or {@link #getFormat24Hour()}
     * depending on whether the user has selected 24-hour format.
     *
     * Calling this method does not schedule or unschedule the time ticker.
     */
    private void chooseFormat() {
        chooseFormat(true);
    }

    /**
     * Returns the current format string. Always valid after constructor has
     * finished, and will never be {@code null}.
     *
     * @hide
     */
    @SuppressWarnings("unused")
    public DateTimeFormatter getFormat() {
        return mFormat;
    }

    /**
     * Selects either one of {@link #getFormat12Hour()} or {@link #getFormat24Hour()}
     * depending on whether the user has selected 24-hour format.
     *
     * @param handleTicker true if calling this method should schedule/unschedule the
     *                     time ticker, false otherwise
     */
    private void chooseFormat(boolean handleTicker) {
        final boolean format24Requested = is24HourModeEnabled();

        //LocaleData ld = LocaleData.get(getContext().getResources().getConfiguration().locale);

        if (format24Requested) {
            mFormat = firstNonNull(mFormat24, mFormat12, DEFAULT_FORMAT_12_HOUR);
        } else {
            mFormat = firstNonNull(mFormat12, mFormat24, DEFAULT_FORMAT_24_HOUR);
        }

        boolean hadSeconds = mHasSeconds;
        mHasSeconds = mFormat.print(TEST_TIME).contains("03");

        if (hasWatchface()) {
            if (handleTicker && mAttached && hadSeconds != mHasSeconds) {
                if (hadSeconds) getWatchface().getHandler().removeCallbacks(mTicker);
                else mTicker.run();
            }
        }
    }

    /**
     * Returns the first item that is not null
     */
    @SafeVarargs
    private static <T> T firstNonNull(T... items) {
        for(T item : items) {
            if(item != null) {
                return item;
            }
        }
        return null;
    }

    public void onAttachedToWindow() {
        if (!mAttached) {
            mAttached = true;

            registerReceiver();
            registerObserver();

            if (hasWatchface() && getWatchface().handleSecondsInDimMode()) {
                alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, 1000, getPendingIntent());
            }

            if (mHasSeconds) {
                mTicker.run();
            } else {
                onTimeChanged();
            }
        }
    }

    public void onDetachedFromWindow() {
        if (mAttached) {
            unregisterReceiver();
            unregisterObserver();

            if (hasWatchface()) {
                getWatchface().getHandler().removeCallbacks(mTicker);

                if (getWatchface().handleSecondsInDimMode()) {
                    alarmManager.cancel(getPendingIntent());
                }
            }

            mAttached = false;
        }
    }

    private PendingIntent getPendingIntent() {
        return hasWatchface()
                ? PendingIntent.getBroadcast(getWatchface().getContext(), 0,
                new Intent(ACTION_KEEP_WATCHFACE_AWAKE), 0) : null;
    }

    private void registerReceiver() {
        final IntentFilter filter = new IntentFilter();

        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(ACTION_KEEP_WATCHFACE_AWAKE);

        if (hasWatchface()) {
            getWatchface().getContext()
                    .registerReceiver(mIntentReceiver, filter, null, getWatchface().getHandler());
            getWatchface().getContext()
                    .registerReceiver(mBatInfoReceiver,
                            new IntentFilter(Intent.ACTION_BATTERY_CHANGED), null,
                            getWatchface().getHandler());
        }
    }

    private void registerObserver() {
        if (hasWatchface()) {
            final Context context = getWatchface().getContext();
            final ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(Settings.System.CONTENT_URI, true,
                    mFormatChangeObserver);
        }
    }

    private void unregisterReceiver() {
        if (hasWatchface()) {
            getWatchface().getContext().unregisterReceiver(mIntentReceiver);
            getWatchface().getContext().unregisterReceiver(mBatInfoReceiver);
        }
    }

    private void unregisterObserver() {
        if (hasWatchface()) {
            final Context context = getWatchface().getContext();
            final ContentResolver resolver = context.getContentResolver();
            resolver.unregisterContentObserver(mFormatChangeObserver);
        }
    }

    private void onTimeChanged() {
        if (hasWatchface()) {
            getWatchface().onTimeChanged(DateTime.now());
        }
    }

    private void onBatteryLevelChanged(int percentage) {
        if (hasWatchface()) {
            getWatchface().onBatteryLevelChanged(percentage);
        }
    }

    private boolean hasWatchface() {
        return watchfaceRef != null && watchfaceRef.get() != null;
    }

    private IWatchface getWatchface() {
        return hasWatchface() ? watchfaceRef.get() : null;
    }
}
