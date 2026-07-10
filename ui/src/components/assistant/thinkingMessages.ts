export const THINKING_MESSAGES = [
    "Claude is working...",
    "Pondering the mysteries of code...",
    "Consulting the silicon oracle...",
    "Rummaging through the codebase...",
    "Herding electrons...",
    "Untangling spaghetti...",
    "Asking the rubber duck...",
    "Brewing a fresh pot of logic...",
    "Warming up the flux capacitor...",
    "Reticulating splines...",
    "Bueller? Bueller? Bueller?...",
    "I'll be back... with an answer...",
    "Wax on, wax off, code on...",
    "Roads? Where we're going we don't need roads...",
    "Using the Force...",
    "Phoning home for help...",
    "Nobody puts Claude in a corner...",
    "Make it so, Number One...",
    "I feel the need... the need for speed...",
    "Live long and process...",
];

let lastIndex = -1;

export function randomThinkingMessage(): string {
    let index;
    do {
        index = Math.floor(Math.random() * THINKING_MESSAGES.length);
    } while (index === lastIndex && THINKING_MESSAGES.length > 1);
    lastIndex = index;
    return THINKING_MESSAGES[index];
}
