#!/usr/bin/env node
import { runMain } from 'citty'
import { buildCmd } from './commands/build.js'
import { checkCmd } from './commands/check.js'
import { devCmd } from './commands/dev.js'
import { initCmd } from './commands/init.js'
import { listCmd } from './commands/list.js'
import { removeCmd } from './commands/remove.js'
import { verifyCmd } from './commands/verify.js'
import { version } from './meta.js'
import { defineCommand } from './utils/args.js'

const main = defineCommand({
  meta: {
    name: 'inu',
    version,
    description: 'build, check and live-reload Inugram plugins',
  },
  subCommands: {
    init: initCmd,
    build: buildCmd,
    check: checkCmd,
    dev: devCmd,
    list: listCmd,
    remove: removeCmd,
    verify: verifyCmd,
  },
})

const rawArgs = process.argv.slice(2)
// citty refuses a bare invocation with an error; the usage is the more useful answer
await runMain(main, { rawArgs: rawArgs.length > 0 ? rawArgs : ['--help'] })
