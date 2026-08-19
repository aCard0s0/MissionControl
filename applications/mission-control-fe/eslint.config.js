// @ts-check
const eslint = require('@eslint/js');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

/**
 * Lint rules for the dashboard. The suites already prove behaviour, so the job
 * here is the things a test structurally cannot see: template accessibility,
 * Angular API misuse, and dead or unreachable code.
 */
module.exports = tseslint.config(
  {
    ignores: ['dist/**', 'coverage/**', '.angular/**', 'node_modules/**'],
  },
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommended,
      ...angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      // `onSaved(_id)` exists to be called, not to use its argument
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      // the app prefixes its own components `mc-`, and the shell is `app-root`
      '@angular-eslint/component-selector': [
        'error',
        { type: 'element', prefix: ['mc', 'app'], style: 'kebab-case' },
      ],
      '@angular-eslint/directive-selector': [
        'error',
        { type: 'attribute', prefix: ['mc', 'app'], style: 'camelCase' },
      ],
    },
  },
  {
    // the specs address the DOM they just rendered, where a missing node should
    // throw rather than be narrowed away, and stub backends are loosely typed
    files: ['**/*.spec.ts', 'src/app/testing/**/*.ts'],
    rules: {
      '@typescript-eslint/no-non-null-assertion': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
  {
    files: ['**/*.html'],
    extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
    rules: {
      // Moving focus into a dialog is what the ARIA dialog pattern asks for; the
      // rule is aimed at page-load autofocus, and every use here is inside a modal.
      '@angular-eslint/template/no-autofocus': 'off',

      // Warnings, not errors: ~137 existing sites fail these three, so making them
      // fail the build would mean either a blanket disable or one sweeping change
      // to every form and overlay in the app. They stay visible, and clearing them
      // is its own pass — see the a11y note in the README.
      '@angular-eslint/template/label-has-associated-control': 'warn',
      '@angular-eslint/template/click-events-have-key-events': 'warn',
      '@angular-eslint/template/interactive-supports-focus': 'warn',
    },
  },
);
