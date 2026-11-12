package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class GoalTypesTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
        // the English keywords of the legacy GOAL_TYPE_KEYWORDS
        "Buy a car, vehicle_purchase",
        "new motorbike, vehicle_purchase",
        "Emergency fund, emergency_fund",
        "Rainy day money, emergency_fund",
        "House down payment, home_purchase",
        "Apartment, home_purchase",
        "Summer vacation, travel",
        "Trip to Japan, travel",
        "Tuition for the course, education",
        "Wedding, wedding",
        // Vietnamese, with and without accents
        "Mua xe máy, vehicle_purchase",
        "mua o to, vehicle_purchase",
        "Mua ô tô, vehicle_purchase",
        "Quỹ khẩn cấp, emergency_fund",
        "quy khan cap, emergency_fund",
        "Mua nhà, home_purchase",
        "Mua căn hộ, home_purchase",
        "Mua chung cư, home_purchase",
        "Du lịch Đà Lạt, travel",
        "Học phí cho con, education",
        "Đám cưới, wedding",
        // nothing matches
        "Quỹ đam mê, general_savings",
        "Tiết kiệm, general_savings",
        "Savings, general_savings"
    })
    void classify_findsTheKindOfGoalFromItsName(String name, String expected) {
        assertThat(GoalTypes.classify(name)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" is not a vehicle")
    @CsvSource({
        // the legacy substring match found "xe" in "xem" and "car" in "scarf"; whole words only
        "Xem phim mỗi tuần",
        "Scarf collection",
        "Cardio gym",
        "Autumn trip"
    })
    void classify_matchesWholeWordsOnly(String name) {
        assertThat(GoalTypes.classify(name)).isNotEqualTo("vehicle_purchase");
    }

    @Test
    void theFirstKindInTheLegacyOrderWins() {
        // "emergency" comes before "home" in the legacy dictionary, "vehicle" before everything
        assertThat(GoalTypes.classify("Emergency fund for the house")).isEqualTo("emergency_fund");
        assertThat(GoalTypes.classify("Car for the wedding")).isEqualTo("vehicle_purchase");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void classify_ofNothing_isGeneralSavings(String name) {
        assertThat(GoalTypes.classify(name)).isEqualTo(GoalTypes.GENERAL_SAVINGS);
    }

    @Test
    void normalize_dropsAccentsAndPunctuation() {
        assertThat(GoalTypes.normalize("  Đám-cưới, 2027!  ")).isEqualTo("dam cuoi 2027");
        assertThat(GoalTypes.normalize(null)).isEmpty();
    }
}
