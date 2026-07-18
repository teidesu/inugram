// ==UserScript==
// @name         info test
// @author       teidesu
// @namespace    inugram.dev
// @version      1.0
// @description  exercises the native console.* and inu.info() bindings
// @description:ru-RU  проверяет нативные console.* и inu.info()
// @grant        none
// @plugin-api   1
// @platform     android
// ==/UserScript==

console.log('hello from a bundled plugin')

const i = inu.info()
console.log('platform =', i.platform)
console.log('appVersion =', i.appVersion, 'appBuild =', i.appBuild)
console.log('apiVersion =', i.apiVersion, 'layer =', i.layer)
console.log('language =', i.language)
console.log('header =', JSON.stringify(i.header))

console.info('console.info works')
console.warn('console.warn works')
console.error('console.error works')

// fresh object each call (mutating one must not leak into the next)
inu.info().platform = 'mutated'
console.log('still android? ->', inu.info().platform)
