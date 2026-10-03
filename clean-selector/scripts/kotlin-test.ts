import { runGradle } from './gradle.ts';

await runGradle([
  ':clean-selector:jvmTest',
  ':clean-selector:jsNodeTest',
]);
