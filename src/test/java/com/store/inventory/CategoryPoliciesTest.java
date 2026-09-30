package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** NEW. Guards the extension point: the new categories created every season. */
class CategoryPoliciesTest {

    /**
     * If Marketing adds a value to {@link ProductCategory} and nobody defines its policy, THIS test
     * fails in the build (instead of the problem being discovered in production).
     */
    @Test
    void everyCategoryHasAPolicy() {
        assertEquals(EnumSet.allOf(ProductCategory.class), CategoryPolicies.defaults().definedCategories());
    }

    @Test
    void missingPolicyFailsLoudlyInsteadOfApplyingADefault() {
        CategoryPolicies onlyStandard = new CategoryPolicies(
                Map.of(ProductCategory.STANDARD, CategoryPolicy.unlimited(Duration.ofMinutes(15))));
        assertThrows(IllegalStateException.class, () -> onlyStandard.forCategory(ProductCategory.FLASH_SALE));
    }

    @Test
    void invalidPoliciesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.unlimited(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.limited(Duration.ofMinutes(1), 0));
    }

    @Test
    void categoryWithoutPolicyCannotRegisterAProduct() {
        var service = new InMemoryInventoryService(new MutableClock(), (s, a) -> { },
                new CategoryPolicies(Map.of()), 5);
        assertThrows(IllegalStateException.class, () -> service.registerProduct("SKU", ProductCategory.STANDARD));
    }
}
