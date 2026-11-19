package info.lifeinuk.backend.weather;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static info.lifeinuk.backend.weather.WeatherCityDetection.*;
import static org.assertj.core.api.Assertions.*;

class WeatherCityDetectionTest {
    private static Choice detectFrom(String country, String latitude, String longitude) {
        var headers = new java.util.HashMap<String, String>();
        if (country != null) { headers.put(COUNTRY, country); }
        if (latitude != null) { headers.put(LATITUDE, latitude); }
        if (longitude != null) { headers.put(LONGITUDE, longitude); }
        return detect(headers::get);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // place, latitude, longitude, nearest supported city
            "Westminster,51.4975,-0.1357,london",
            "Brighton,50.8225,-0.1372,london",
            "Coventry,52.4068,-1.5197,birmingham",
            "Salford,53.4875,-2.2901,manchester",
            "Liverpool,53.4084,-2.9916,manchester",
            "York,53.9600,-1.0873,leeds",
            "Newcastle,54.9783,-1.6178,leeds",
            "Paisley,55.8466,-4.4236,glasgow",
            "Aberdeen,57.1497,-2.0943,edinburgh",
            "Swansea,51.6214,-3.9436,cardiff",
            "Bristol,51.4545,-2.5879,cardiff",
            "Derry,54.9966,-7.3086,belfast"})
    void aUkLocationGetsTheNearestSupportedCity(String place, String latitude, String longitude, String expected) {
        var choice = detectFrom("GB", latitude, longitude);
        assertThat(choice.city().id()).as(place).isEqualTo(expected);
        assertThat(choice.source()).isEqualTo(Source.DETECTED);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "null,null,null",          // headers not forwarded (policy not applied yet, or local)
            "FR,48.8566,2.3522",        // outside the UK
            "IE,53.3498,-6.2603",       // Dublin: not GB
            "IM,54.1500,-4.4800",       // Isle of Man: not GB
            "US,40.7128,-74.0060",
            "GB,null,null",             // country only
            "GB,53.48,null",
            "GB,not-a-number,-2.24",
            "GB,1e2,-2.24",
            "GB,48.8566,2.3522",        // claims GB, coordinates in France
            "GB,53.4808,-2.2426;x",
            "gb,53.4808,-2.2426"})
    void anythingElseFallsBackToLondon(String country, String latitude, String longitude) {
        var choice = detectFrom(country, latitude, longitude);
        assertThat(choice.city()).isEqualTo(WeatherCity.LONDON);
        assertThat(choice.source()).isEqualTo(Source.DEFAULT);
    }

    @Test
    void readsOnlyTheThreeCloudFrontLocationHeaders() {
        var asked = new java.util.ArrayList<String>();
        detect(name -> { asked.add(name); return Map.of(COUNTRY, "GB", LATITUDE, "53.48", LONGITUDE, "-2.24").get(name); });
        assertThat(asked).containsExactlyInAnyOrder(COUNTRY, LATITUDE, LONGITUDE);
    }
}
