const lazyImportsBlacklist = require('./lazy-imports-blacklist');

module.exports = function(api, options = {}) {
  const { web = {}, native = {} } = options;
  const isWeb = api.caller(isTargetWeb);
  const platformOptions = isWeb
    ? { disableImportExportTransform: true, ...web }
    : { disableImportExportTransform: false, ...native };

  // Note that if `options.lazyImports` is not set (i.e., `null` or `undefined`),
  // `metro-react-native-babel-preset` will handle it.
  const lazyImportsOption = options && options.lazyImports;

  return {
    presets: [
      [
        // We use `require` here instead of directly using the package name
        // because we want to specifically use the `metro-react-native-babel-preset`
        // installed in this folder's `node_modules` (`babel-preset-expo/node_modules/`).
        // This way the preset will not change unintentionally.
        // Reference: https://github.com/expo/expo/pull/4685#discussion_r307143920
        require('metro-react-native-babel-preset'),
        {
          disableImportExportTransform: platformOptions.disableImportExportTransform,
          lazyImportExportTransform:
            lazyImportsOption === true
              ? importModuleSpecifier => {
                  // Do not lazy-initialize packages that are local imports (similar to `lazy: true` behavior)
                  // or are in the blacklist.
                  return !(
                    importModuleSpecifier.includes('./') ||
                    lazyImportsBlacklist.has(importModuleSpecifier)
                  );
                }
              : // Pass the option directly to `metro-react-native-babel-preset`
                // (which in turns pass it to `babel-plugin-transform-modules-commonjs`).
                lazyImportsOption,
        },
      ],
    ],
    plugins: [
      [
        'babel-plugin-module-resolver',
        {
          alias: {
            'react-native-vector-icons': '@expo/vector-icons',
          },
        },
      ],
      ['@babel/plugin-proposal-decorators', { legacy: true }],
      isWeb && ['babel-plugin-react-native-web'],
    ].filter(Boolean),
  };
};

function isTargetWeb(caller) {
  return caller && caller.name === 'babel-loader';
}
