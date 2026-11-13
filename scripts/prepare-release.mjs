import {existsSync, readFileSync, realpathSync, writeFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {compareVersions, parseVersion, projectVersionPattern, readProjectVersion} from './release-version.mjs';

const repositoryRoot = dirname(dirname(fileURLToPath(import.meta.url)));

export function prepareRelease(version, root = repositoryRoot) {
  parseVersion(version);
  const buildPath = join(root, 'build.gradle.kts');
  const catalogPath = join(root, 'namastack-once-examples/gradle/libs.versions.toml');
  const build = readFileSync(buildPath, 'utf8');
  const catalog = readFileSync(catalogPath, 'utf8');
  if (compareVersions(version, readProjectVersion(build)) < 0) {
    throw new Error('Release preparation cannot lower the project version');
  }
  const catalogPattern = /^namastackOnce = "[^"]+"$/gm;
  if ([...catalog.matchAll(catalogPattern)].length !== 1) {
    throw new Error('Expected exactly one namastackOnce example version');
  }
  const updatedBuild = build.replace(projectVersionPattern,
    `    version = "${version}" + if (!isRelease) "-SNAPSHOT" else ""`);
  const updatedCatalog = catalog.replace(catalogPattern, `namastackOnce = "${version}-SNAPSHOT"`);
  writeFileSync(buildPath, updatedBuild);
  writeFileSync(catalogPath, updatedCatalog);
}

if (process.argv[1] && existsSync(process.argv[1]) && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    prepareRelease(process.argv[2]);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
