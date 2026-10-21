import { useState } from "react";
import {
    Button,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    Switch,
    Tooltip,
} from "@patternfly/react-core";
import { setAllowAll } from "../../config/api";
import "./AllowAllSwitch.css";

/** Props for {@link AllowAllSwitch}. */
export interface AllowAllSwitchProps {
    /** The assistant session whose permissions are auto-approved. */
    sessionId: string;
    /** Whether Allow All is currently on. */
    allowAll: boolean;
    /** Called with the new state after the server accepted the change. */
    onChange: (enabled: boolean) => void;
    /** Called with a message when the server rejected the change. */
    onError: (message: string) => void;
    /** Optional element id for the switch; defaults to "allow-all-toggle". */
    id?: string;
}

/**
 * The "Allow All" switch for an AI assistant session: when on, every tool permission request (except
 * questions to the user) is approved automatically. Turning it on asks for confirmation first; turning it
 * off does not.
 *
 * @param props the switch props
 * @returns the switch and its confirmation dialog
 */
export function AllowAllSwitch({ sessionId, allowAll, onChange, onError, id = "allow-all-toggle" }: AllowAllSwitchProps) {
    const [confirmOpen, setConfirmOpen] = useState<boolean>(false);

    const apply = async (enabled: boolean): Promise<void> => {
        try {
            await setAllowAll(sessionId, enabled);
            onChange(enabled);
        } catch (err) {
            console.error("Failed to change Allow All:", err);
            onError((err as Error).message
                || (enabled ? "Failed to enable Allow All" : "Failed to disable Allow All"));
        }
    };

    return (
        <>
            <Tooltip content={allowAll
                ? "All tool permissions are being auto-approved"
                : "Auto-approve all tool permissions for this session"}>
                <Switch
                    id={id}
                    label="Allow All"
                    isChecked={allowAll}
                    onChange={(_event, checked) => {
                        if (checked) {
                            setConfirmOpen(true);
                        } else {
                            void apply(false);
                        }
                    }}
                    className={`axiom-allow-all-switch${allowAll ? " axiom-allow-all-switch--active" : ""}`}
                />
            </Tooltip>
            <Modal
                isOpen={confirmOpen}
                onClose={() => setConfirmOpen(false)}
                variant="small"
                aria-label="Enable Allow All confirmation"
            >
                <ModalHeader title="Enable Allow All?" />
                <ModalBody>
                    All tool invocations will be approved automatically without prompting.
                    This includes file modifications, command execution, and any other tool
                    actions. You can disable this at any time.
                </ModalBody>
                <ModalFooter>
                    <Button variant="warning" onClick={() => { setConfirmOpen(false); void apply(true); }}>
                        Enable
                    </Button>
                    <Button variant="link" onClick={() => setConfirmOpen(false)}>
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>
        </>
    );
}
