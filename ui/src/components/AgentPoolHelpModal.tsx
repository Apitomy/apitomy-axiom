import { Button, Content, Modal, ModalBody, ModalFooter, ModalHeader } from "@patternfly/react-core";

interface AgentPoolHelpModalProps {
    isOpen: boolean;
    onClose: () => void;
}

/** Explains how configured agents form a pool that controls how many AI tasks run concurrently. */
export function AgentPoolHelpModal({ isOpen, onClose }: AgentPoolHelpModalProps) {
    return (
        <Modal isOpen={isOpen} onClose={onClose} variant="medium" aria-labelledby="agent-pool-help-title">
            <ModalHeader title="How the agent pool works" labelId="agent-pool-help-title" />
            <ModalBody>
                <Content>
                    <p>
                        Each agent is a <strong>slot</strong> that runs one AI task at a time. Together, the
                        enabled agents form a pool, and the size of the pool is the number of AI tasks that can
                        run at the same time. With three enabled agents, at most three agent-mode action types
                        run concurrently; a fourth task waits until an agent becomes free.
                    </p>
                    <h3>Why create several agents</h3>
                    <ul>
                        <li><strong>More parallelism:</strong> add agents to run more workflows in parallel.</li>
                        <li>
                            <strong>Less load or cost:</strong> keep the pool small to limit concurrent AI usage,
                            API rate limits and spend.
                        </li>
                        <li>
                            <strong>Different engines:</strong> an agent has a type (Claude Code, OpenCode, Copilot).
                            An action type whose engine is set only runs on agents of that type, so create agents
                            of each engine you use.
                        </li>
                    </ul>
                    <h3>Capabilities</h3>
                    <p>
                        Capabilities are glob patterns that decide which work an agent accepts. A task for action
                        type <code>X</code> asks for <code>action:X</code>; <code>*</code> or <code>action:*</code>
                        accepts everything. Scope capabilities to reserve agents: for example, give
                        <code> action:security-review</code> to a single agent so that work runs one at a time,
                        while other agents with <code>action:*</code> handle everything else in parallel.
                    </p>
                    <h3>When no agent is free</h3>
                    <p>
                        If a matching agent exists but is busy, the task waits in the queue. If no enabled agent
                        matches the action type&apos;s engine, the task fails with a message naming the missing
                        engine. Disable an agent to take it out of the pool without deleting it.
                    </p>
                    <p>
                        Script and Node.js action types do not use agents; they are not limited by the pool.
                    </p>
                </Content>
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" onClick={onClose}>Close</Button>
            </ModalFooter>
        </Modal>
    );
}
