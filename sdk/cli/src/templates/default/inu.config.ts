import { defineConfig } from '@inugram/cli'

export default defineConfig({
  manifest: {
    author: '__AUTHOR__',
  },
  plugins: {
    hello: 'src/hello/index.ts',
  },
})
