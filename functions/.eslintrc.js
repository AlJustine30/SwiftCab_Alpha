module.exports = {
  env: {
    es6: true,
    node: true,
  },
  parserOptions: {
    "ecmaVersion": 2020,
  },
  extends: [
    "eslint:recommended",
    "google",
  ],
  rules: {
    "no-restricted-globals": ["error", "name", "length"],
    "prefer-arrow-callback": "off",
    "quotes": "off",
    "linebreak-style": "off",
    "indent": "off",
    "max-len": "off",
    "object-curly-spacing": "off",
    "require-jsdoc": "off",
    "valid-jsdoc": "off",
    "operator-linebreak": "off",
    "no-empty": "off",
    "comma-dangle": "off",
    "no-trailing-spaces": "off",
    "no-multiple-empty-lines": "off",
    "padded-blocks": "off",
  },
  overrides: [
    {
      files: ["**/*.spec.*"],
      env: {
        mocha: true,
      },
      rules: {},
    },
  ],
  globals: {},
};
