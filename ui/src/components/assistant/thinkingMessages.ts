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
    "Consulting the ancient scrolls of Stack Overflow...",
    "Feeding the hamsters that power the servers...",
    "Aligning the bits in alphabetical order...",
    "Negotiating with the compiler...",
    "Convincing the semicolons to stay put...",
    "Teaching the algorithms to dance...",
    "Polishing the pixels...",
    "Calibrating the sarcasm detector...",
    "Downloading more RAM...",
    "Performing mass calculations on a potato...",
    "Converting caffeine into code...",
    "Waking up the night shift neurons...",
    "Checking if it works on my machine...",
    "Refactoring the space-time continuum...",
    "Asking the magic 8-ball for guidance...",
    "Counting backwards from infinity...",
    "Debugging the debugger...",
    "Searching for the any key...",
    "Spinning up the hamster wheel...",
    "Translating from human to binary...",
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
