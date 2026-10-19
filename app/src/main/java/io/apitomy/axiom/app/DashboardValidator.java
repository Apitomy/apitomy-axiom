package io.apitomy.axiom.app;

import io.apitomy.axiom.api.beans.DashboardTab;
import io.apitomy.axiom.api.beans.DashboardWidget;
import io.apitomy.axiom.api.beans.DashboardWidgetLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates the structure of a dashboard (name, tabs and widgets) before it is stored. Used by the
 * dashboards REST API and by configuration pack import.
 */
public final class DashboardValidator {

    private DashboardValidator() {
    }

    /**
     * Validates a dashboard and returns every problem found.
     *
     * @param name the dashboard name
     * @param tabs the dashboard tabs (null is treated as no tabs)
     * @return human-readable problems; empty when the dashboard is valid
     */
    public static List<String> validate(String name, List<DashboardTab> tabs) {
        List<String> problems = new ArrayList<>();
        if (name == null || name.isBlank()) {
            problems.add("Dashboard name must not be blank");
        }
        if (tabs == null) {
            return problems;
        }
        Set<String> tabIds = new HashSet<>();
        Set<String> widgetIds = new HashSet<>();
        for (int t = 0; t < tabs.size(); t++) {
            DashboardTab tab = tabs.get(t);
            String tabRef = "tabs[" + t + "]";
            if (tab == null) {
                problems.add(tabRef + " must not be null");
                continue;
            }
            if (isBlank(tab.getId())) {
                problems.add(tabRef + ".id must not be blank");
            } else if (!tabIds.add(tab.getId())) {
                problems.add(tabRef + ".id '" + tab.getId() + "' is duplicated");
            }
            if (isBlank(tab.getName())) {
                problems.add(tabRef + ".name must not be blank");
            }
            if (tab.getWidgets() == null) {
                problems.add(tabRef + ".widgets must be an array");
                continue;
            }
            for (int w = 0; w < tab.getWidgets().size(); w++) {
                validateWidget(tab.getWidgets().get(w), tabRef + ".widgets[" + w + "]", widgetIds, problems);
            }
        }
        return problems;
    }

    private static void validateWidget(DashboardWidget widget, String ref, Set<String> widgetIds,
                                       List<String> problems) {
        if (widget == null) {
            problems.add(ref + " must not be null");
            return;
        }
        if (isBlank(widget.getId())) {
            problems.add(ref + ".id must not be blank");
        } else if (!widgetIds.add(widget.getId())) {
            problems.add(ref + ".id '" + widget.getId() + "' is duplicated");
        }
        if (isBlank(widget.getType())) {
            problems.add(ref + ".type must not be blank");
        }
        DashboardWidgetLayout layout = widget.getLayout();
        if (layout == null) {
            problems.add(ref + ".layout is required");
            return;
        }
        if (layout.getX() == null || layout.getX() < 0) {
            problems.add(ref + ".layout.x must be a non-negative integer");
        }
        if (layout.getY() == null || layout.getY() < 0) {
            problems.add(ref + ".layout.y must be a non-negative integer");
        }
        if (layout.getW() == null || layout.getW() < 1) {
            problems.add(ref + ".layout.w must be a positive integer");
        }
        if (layout.getH() == null || layout.getH() < 1) {
            problems.add(ref + ".layout.h must be a positive integer");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
