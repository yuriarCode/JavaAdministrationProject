package com.store.inventory;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Table of policies per {@link ProductCategory}.
 *
 * NEW. Marketing creates a new category every season, but the enum lives in the {@code api}
 * package (which cannot be modified from this repo). That is why there is NO default policy here:
 * if someone adds a value to the enum and forgets to define its rule, {@link #forCategory} fails
 * explicitly instead of silently applying the wrong rules (for example, no unit limit). In addition,
 * {@code CategoryPoliciesTest} fails the build if any category is missing.
 *
 * <p>To add a category: (1) add the value to the enum, (2) add one line in {@link #defaults()}.
 */
final class CategoryPolicies {

    private final Map<ProductCategory, CategoryPolicy> policies;

    CategoryPolicies(Map<ProductCategory, CategoryPolicy> policies) {
        Objects.requireNonNull(policies, "policies");
        // Defensive copy into an EnumMap: not affected by later changes to the caller's map.
        this.policies = new EnumMap<>(ProductCategory.class);
        this.policies.putAll(policies);
    }

    /** Current rules as described in the README. This is the ONLY place where they are defined. */
    static CategoryPolicies defaults() {
        Map<ProductCategory, CategoryPolicy> table = new EnumMap<>(ProductCategory.class);
        table.put(ProductCategory.STANDARD, CategoryPolicy.unlimited(Duration.ofMinutes(15)));
        // Paid by bank transfer, hence the 24-hour window.
        table.put(ProductCategory.PRE_ORDER, CategoryPolicy.unlimited(Duration.ofHours(24)));
        table.put(ProductCategory.FLASH_SALE, CategoryPolicy.limited(Duration.ofMinutes(5), 2));
        return new CategoryPolicies(table);
    }

    /**
     * @throws IllegalStateException if the category has no policy defined (a configuration error,
     *                               not a client error)
     */
    CategoryPolicy forCategory(ProductCategory category) {
        CategoryPolicy policy = policies.get(Objects.requireNonNull(category, "category"));
        if (policy == null) {
            throw new IllegalStateException("No policy defined for category " + category
                    + ". Add it in CategoryPolicies.defaults().");
        }
        return policy;
    }

    /** Categories that have a policy (used by the test that guards the enum). */
    Set<ProductCategory> definedCategories() {
        return Collections.unmodifiableSet(policies.keySet());
    }
}
