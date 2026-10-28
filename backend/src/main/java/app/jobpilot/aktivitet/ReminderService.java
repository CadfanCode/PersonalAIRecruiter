package app.jobpilot.aktivitet;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;

import org.springframework.stereotype.Service;

import app.jobpilot.aktivitet.AktivitetModel.Reminder;

/** The monthly report window (SPEC 10): it opens on the 1st and closes on the 16th of the following month. */
@Service
public class ReminderService {

    static final int LAST_DAY = 16;

    private final Clock clock;

    public ReminderService(Clock clock) {
        this.clock = clock;
    }

    public Reminder reminder() {
        return reminder(LocalDate.now(clock));
    }

    static Reminder reminder(LocalDate today) {
        int day = today.getDayOfMonth();
        YearMonth reported = YearMonth.from(today).minusMonths(1);
        String month = reported.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + reported.getYear();
        if (day > LAST_DAY) {
            return new Reminder(false, day, 0, reported.toString(), "");
        }
        int left = LAST_DAY - day;
        return new Reminder(true, day, left, reported.toString(), "The aktivitetsrapport for " + month + " can be submitted until the 16th ("
                + (left == 0 ? "today is the last day" : left + (left == 1 ? " day left" : " days left")) + "). Log your applied jobs first. "
                + "JobPilot never submits the report: do that yourself on Arbetsförmedlingen.");
    }
}
