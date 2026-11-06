import { defineConfig, type Plugin } from 'vite';
import path from 'node:path';
import JavaScriptObfuscator from 'javascript-obfuscator';

/**
 * Obfuscates the built FRIDAY bundle so the shipped APK is hard to read if someone decompiles it
 * (the source in the repo is untouched). Settings are deliberately conservative: object keys are
 * NOT renamed and control-flow flattening is off, because the Live protocol and the Java bridge
 * rely on exact JSON keys and method names, and the audio loop must stay fast. Strings (prompts,
 * action names) are moved into an encoded array and decoded at runtime, so they are not readable
 * as plain text. Turn it off with FRIDAY_NO_OBFUSCATE=1 for a readable debug build.
 */
function obfuscate(): Plugin {
  return {
    name: 'friday-obfuscate',
    apply: 'build',
    enforce: 'post',
    generateBundle(_options, bundle) {
      if (process.env.FRIDAY_NO_OBFUSCATE === '1') return;
      for (const file of Object.values(bundle)) {
        if (file.type !== 'chunk' || !file.fileName.endsWith('.js')) continue;
        file.code = JavaScriptObfuscator.obfuscate(file.code, {
          compact: true,
          identifierNamesGenerator: 'mangled',
          renameGlobals: false,
          // Must stay false: the Gemini Live JSON and the window.MyraaNative bridge use exact keys.
          transformObjectKeys: false,
          stringArray: true,
          stringArrayThreshold: 0.75,
          stringArrayEncoding: ['base64'],
          stringArrayWrappersType: 'variable',
          splitStrings: true,
          splitStringsChunkLength: 10,
          // Off on purpose: these slow hot paths (the audio loop) and can break minified code.
          controlFlowFlattening: false,
          deadCodeInjection: false,
          selfDefending: false,
          numbersToExpressions: true,
          simplify: true,
          unicodeEscapeSequence: false,
          target: 'browser',
        }).getObfuscatedCode();
      }
    },
  };
}

export default defineConfig({
  base: './',
  resolve: { alias: { '@': path.resolve(__dirname, '.') } },
  plugins: [obfuscate()],
  build: { outDir: 'android/app/src/main/assets', emptyOutDir: true,
    rollupOptions: { input: 'mobile.html' }, target: 'es2020', chunkSizeWarningLimit: 1600 },
});
