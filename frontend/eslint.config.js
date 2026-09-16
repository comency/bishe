import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import vue from 'eslint-plugin-vue'

export default tseslint.config(
  { ignores: ['dist/**', 'coverage/**', 'node_modules/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...vue.configs['flat/essential'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      globals: { AbortController: 'readonly', TextEncoder: 'readonly', URLSearchParams: 'readonly', setInterval: 'readonly', clearInterval: 'readonly' },
      parserOptions: { parser: tseslint.parser },
    },
  },
)
