// Mirrors the style rules `yarn lint` enforces through the @jahia ESLint config, so that formatting
// a file never produces code the linter then rejects. Each option names the rule it satisfies; if
// you change one, change the other.
module.exports = {
    semi: true, // semi: always
    bracketSpacing: false, // object-curly-spacing: never
    arrowParens: 'avoid', // arrow-parens: as-needed
    trailingComma: 'none', // comma-dangle: never
    singleQuote: true, // quotes: single
    quoteProps: 'as-needed', // quote-props: as-needed
    tabWidth: 4, // indent: 4
    printWidth: 120 // no max-len rule; matches the existing code
};
