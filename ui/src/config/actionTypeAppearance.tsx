import type { ReactNode } from "react";
import CubesIcon from "@patternfly/react-icons/dist/esm/icons/cubes-icon";
import RobotIcon from "@patternfly/react-icons/dist/esm/icons/robot-icon";

/**
 * Accent color for Action nodes that call an Axiom SDK function directly ({@code sdk:} prefix). Palette
 * mid-tones are used because PatternFly's theme-aware nonstatus tokens are pale label tints.
 */
export const SDK_ACTION_COLOR = "var(--pf-t--color--teal--50, #37a3a3)";

/**
 * Accent color for Action nodes whose action type runs an AI agent. Purple rather than the agent label's blue,
 * because the default Action node border is already blue.
 */
export const AGENT_ACTION_COLOR = "var(--pf-t--color--purple--50, #5e40be)";

/** The icon and accent color the workflow editor and viewer use for an Action node. */
export interface ActionTypeLook {
    icon?: ReactNode;
    color?: string;
}

/**
 * Chooses how Action nodes of an action type look in the workflow editor and viewer. SDK calls and AI agent
 * action types get their own icon and color; everything else keeps the default look.
 *
 * @param value the action type value used in the workflow ({@code sdk:<function>} for SDK calls)
 * @param executionMode the action type's execution mode, when it is a configured action type
 * @returns the icon and color, or an empty object for the default look
 */
export function actionTypeAppearance(value: string, executionMode: string | undefined): ActionTypeLook {
    if (value.startsWith("sdk:")) {
        return { icon: <CubesIcon />, color: SDK_ACTION_COLOR };
    }
    if (executionMode === "agent") {
        return { icon: <RobotIcon />, color: AGENT_ACTION_COLOR };
    }
    return {};
}
