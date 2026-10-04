package info.lifeinuk.backend.places;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static info.lifeinuk.backend.places.PlaceSearchFailure.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** OS Names-shaped offline fixtures (documented find response fields); no live provider access. */
class PlaceSearchTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T15:00:00Z"), ZoneOffset.UTC);

    static String entry(String id, String name, String type, double x, double y, String extra) {
        return """
            {"GAZETTEER_ENTRY":{"ID":"%s","NAMES_URI":"http://data.ordnancesurvey.co.uk/id/%s","NAME1":"%s","TYPE":"populatedPlace",
             "LOCAL_TYPE":"%s","GEOMETRY_X":%s,"GEOMETRY_Y":%s,"MOST_DETAIL_VIEW_RES":25000,"LEAST_DETAIL_VIEW_RES":1000000,
             "MBR_XMIN":%s,"MBR_YMIN":%s,"MBR_XMAX":%s,"MBR_YMAX":%s%s}}
            """.formatted(id, id, name, type, x, y, x - 10, y - 10, x + 10, y + 10, extra);
    }
    static String page(int total, String... entries) {
        return """
            {"header":{"uri":"https://api.os.uk/search/names/v1/find?query=x","query":"x","format":"JSON",
             "maxresults":10,"offset":0,"totalresults":%d},"results":[%s]}
            """.formatted(total, String.join(",", entries));
    }
    static PlaceSearch searching(String body) {
        OsNamesClient client = mock(OsNamesClient.class);
        when(client.find(anyString())).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return new PlaceSearch(client, CLOCK);
    }

    @Test
    void cityQueryReturnsNormalisedCandidateWithWgs84CoordinatesAndAttribution() {
        var response = searching(page(1, entry("osgb4000000074564391", "Sheffield", "City", 435_000, 387_000,
                ",\"DISTRICT_BOROUGH\":\"Sheffield\",\"COUNTY_UNITARY\":\"Sheffield\",\"REGION\":\"Yorkshire and the Humber\",\"COUNTRY\":\"England\"")))
                .search("  Sheffield ");
        assertThat(response.query()).isEqualTo("Sheffield");
        assertThat(response.attribution()).isEqualTo("Contains OS data © Crown copyright and database right 2026");
        var place = response.places().getFirst();
        assertThat(place.id()).isEqualTo("osgb4000000074564391");
        assertThat(place.name()).isEqualTo("Sheffield");
        assertThat(place.type()).isEqualTo("city");
        assertThat(place.label()).isEqualTo("Sheffield, Yorkshire and the Humber");
        assertThat(place.area()).isNull();
        assertThat(place.region()).isEqualTo("Yorkshire and the Humber");
        assertThat(place.country()).isEqualTo("England");
        var expected = BritishNationalGrid.toWgs84(435_000, 387_000);
        assertThat(place.latitude()).isEqualTo(expected.latitude());
        assertThat(place.longitude()).isEqualTo(expected.longitude());
    }

    @Test
    void postcodeQueryLabelsThePostcodeWithItsPlace() {
        var place = searching(page(1, entry("S12HE", "S1 2HE", "Postcode", 435_380, 387_120,
                ",\"POSTCODE_DISTRICT\":\"S1\",\"POPULATED_PLACE\":\"Sheffield\",\"DISTRICT_BOROUGH\":\"Sheffield\",\"REGION\":\"Yorkshire and the Humber\",\"COUNTRY\":\"England\"")))
                .search("S1 2HE").places().getFirst();
        assertThat(place.type()).isEqualTo("postcode");
        assertThat(place.label()).isEqualTo("S1 2HE, Sheffield, Yorkshire and the Humber");
        assertThat(place.area()).isEqualTo("Sheffield");
    }

    @Test
    void multipleCandidatesKeepProviderOrderAndUnsupportedTypesAreDropped() {
        var places = searching(page(4,
                entry("a", "Newport", "Town", 331_000, 188_000, ",\"COUNTY_UNITARY\":\"Newport\",\"REGION\":\"Wales\",\"COUNTRY\":\"Wales\""),
                entry("b", "Newport Road", "Named Road", 331_100, 188_100, ""),
                entry("c", "Newport", "Town", 449_000, 89_000, ",\"COUNTY_UNITARY\":\"Isle of Wight\",\"REGION\":\"South East\",\"COUNTRY\":\"England\""),
                entry("d", "Newport", "Suburban Area", 450_000, 500_000, ",\"POPULATED_PLACE\":\"Middlesbrough\",\"REGION\":\"North East\"")))
                .search("Newport").places();
        assertThat(places).extracting(PlaceSearchResponse.Place::id).containsExactly("a", "c", "d");
        assertThat(places).extracting(PlaceSearchResponse.Place::label)
                .containsExactly("Newport, Wales", "Newport, Isle of Wight, South East", "Newport, Middlesbrough, North East");
        assertThat(places.get(2).type()).isEqualTo("suburb");
    }

    @Test
    void resultsAreBoundedToTheConfiguredMaximum() {
        String[] entries = IntStream.range(0, 15).mapToObj(i -> entry("id" + i, "Place " + i, "Village", 400_000 + i, 300_000, ""))
                .toArray(String[]::new);
        assertThat(searching(page(15, entries)).search("Place").places()).hasSize(OsNamesClient.MAX_RESULTS)
                .extracting(PlaceSearchResponse.Place::id).startsWith("id0", "id1");
    }

    @Test
    void noMatchIsASuccessfulEmptyResultWhetherResultsIsAbsentOrEmpty() {
        assertThat(searching("{\"header\":{\"totalresults\":0}}").search("Qwxz").places()).isEmpty();
        assertThat(searching(page(0)).search("Qwxz").places()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json", "[]", "{}", "{\"header\":{}}", "{\"header\":{\"totalresults\":2}}",
        "{\"header\":{\"totalresults\":1},\"results\":{}}", "{\"header\":{\"totalresults\":1},\"results\":[{}]}",
        "{\"header\":{\"totalresults\":1},\"results\":[{\"GAZETTEER_ENTRY\":{\"ID\":\"x\",\"LOCAL_TYPE\":\"Town\",\"GEOMETRY_X\":1,\"GEOMETRY_Y\":1}}]}",
        "{\"header\":{\"totalresults\":1},\"results\":[{\"GAZETTEER_ENTRY\":{\"ID\":\"x\",\"NAME1\":\"X\",\"LOCAL_TYPE\":\"Town\",\"GEOMETRY_X\":\"1\",\"GEOMETRY_Y\":1}}]}",
        "{\"header\":{\"totalresults\":1},\"results\":[{\"GAZETTEER_ENTRY\":{\"ID\":\"x\",\"NAME1\":\"X\",\"LOCAL_TYPE\":\"Town\",\"GEOMETRY_Y\":1}}]}",
        "{\"header\":{\"totalresults\":1},\"results\":[{\"GAZETTEER_ENTRY\":{\"ID\":\"x\",\"NAME1\":\"X\",\"LOCAL_TYPE\":\"Town\",\"GEOMETRY_X\":-5,\"GEOMETRY_Y\":1}}]}",
        "{\"header\":{\"totalresults\":1},\"results\":[{\"GAZETTEER_ENTRY\":{\"ID\":\"x\",\"NAME1\":\"X\",\"LOCAL_TYPE\":\"Town\",\"GEOMETRY_X\":1,\"GEOMETRY_Y\":1,\"REGION\":7}}]}",
        "{\"header\":{\"totalresults\":0}} trailing", "{\"header\":{\"totalresults\":0,\"totalresults\":0}}"
    })
    void malformedOrIncompleteProviderResponsesFailClosed(String body) {
        assertThatThrownBy(() -> searching(body).search("Sheffield")).isInstanceOf(PlaceSearchFailure.class)
                .extracting(failure -> ((PlaceSearchFailure) failure).reason()).isEqualTo(INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "line\u0000break", "tab\u0007bell"})
    void blankOrControlQueriesAreRejectedBeforeAnyProviderCall(String query) {
        OsNamesClient client = mock(OsNamesClient.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new PlaceSearch(client, CLOCK).search(query));
        verifyNoInteractions(client);
    }

    @Test
    void oversizedQueryIsRejectedAndBoundaryLengthIsAccepted() {
        OsNamesClient client = mock(OsNamesClient.class);
        when(client.find(anyString())).thenReturn(page(0).getBytes(StandardCharsets.UTF_8));
        var search = new PlaceSearch(client, CLOCK);
        assertThat(search.search("a".repeat(PlaceSearch.MAX_QUERY_LENGTH)).places()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> search.search("a".repeat(PlaceSearch.MAX_QUERY_LENGTH + 1)));
        assertThatIllegalArgumentException().isThrownBy(() -> search.search(null));
        verify(client, times(1)).find(anyString());
        assertThat(PlaceSearch.normalise("  Sheffield   City  Centre ")).isEqualTo("Sheffield City Centre");
    }

    @Test
    void upstreamFailureReasonsPropagateUnchanged() {
        for (var reason : PlaceSearchFailure.Reason.values()) {
            OsNamesClient client = mock(OsNamesClient.class);
            when(client.find(anyString())).thenThrow(new PlaceSearchFailure(reason));
            assertThatThrownBy(() -> new PlaceSearch(client, CLOCK).search("Leeds"))
                    .isInstanceOf(PlaceSearchFailure.class).hasMessage(reason.name());
        }
    }
}
