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
    "I'll be back... with an answer...",
    "Using the Force...",
    "Phoning home for help...",
    "Nobody puts Claude in a corner...",
    "Making it so...",
    "Feeling the need... the need for speed...",
    "Living long and processing...",
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
