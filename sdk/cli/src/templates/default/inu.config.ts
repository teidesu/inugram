import { defineConfig } from '@inugram/cli'

export default defineConfig({
  plugins: {
    hello: {
      entry: 'src/hello/index.ts',
      manifest: {
        id: '__ID__',
        name: 'Hello',
        author: '__AUTHOR__',
        version: '1.0.0',
        description: 'a starting point',
        icon: 'inu://bot',
      },
    },
  },
})
