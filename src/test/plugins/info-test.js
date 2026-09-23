// ==InuPlugin==
// @name         info test
// @description  asserts inu.info() describes the app it runs in and hands back a fresh object
// @description:ru-RU  проверяет inu.info()
// @grant        openUrl
// @grant        onAppVisibilityChange
// ==/InuPlugin==

const i = inu.info()
console.log(`app ${i.appVersion} (${i.appBuild}), api ${i.apiVersion}, layer ${i.layer}, language ${i.language}`)
console.log('header =', JSON.stringify(i.header))

check('platform names the build this is', i.platform === 'android', i.platform)
check(
  'appVersion and appBuild are non-empty strings',
  typeof i.appVersion === 'string' && i.appVersion !== '' && typeof i.appBuild === 'string' && i.appBuild !== '',
  `${i.appVersion}/${i.appBuild}`,
)
check('apiVersion is a positive integer', Number.isInteger(i.apiVersion) && i.apiVersion >= 1, i.apiVersion)
check('layer is a positive integer', Number.isInteger(i.layer) && i.layer >= 1, i.layer)
check('language is a string', typeof i.language === 'string', JSON.stringify(i.language))

function checkHeaderEntry(key, expected) {
  const value = i.header[key]
  check(`header.${key} is an array of ${expected}`, Array.isArray(value) && value.length === expected, JSON.stringify(value))
}

// two @grant lines: fails both a runtime that array-wraps without regrouping and a joined string
checkHeaderEntry('grant', 2)
checkHeaderEntry('name', 1)
checkHeaderEntry('description', 1)
checkHeaderEntry('description:ru-ru', 1)
check('the base key is lowercased, so a locale-cased directive is not a second key', !('description:ru-RU' in i.header))

console.info('console.info works')
console.warn('console.warn works')
console.error('console.error works')

i.platform = 'mutated'
check('info() builds a fresh object each call', inu.info().platform === 'android', inu.info().platform)

console.log('info test done')
