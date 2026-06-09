import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** EnumSet and EnumMap usage. */
public class C_EnumCollections {

    public enum Day {
        MON, TUE, WED, THU, FRI, SAT, SUN
    }

    public static Set<Day> weekend() {
        return EnumSet.of(Day.SAT, Day.SUN);
    }

    public static Set<Day> workdays() {
        return EnumSet.range(Day.MON, Day.FRI);
    }

    public static Set<Day> allDays() {
        return EnumSet.allOf(Day.class);
    }

    public static Map<Day, String> labels() {
        Map<Day, String> map = new EnumMap<Day, String>(Day.class);
        map.put(Day.MON, "Monday");
        map.put(Day.FRI, "Friday");
        return map;
    }

    public static boolean isWeekend(Day d) {
        return weekend().contains(d);
    }
}
