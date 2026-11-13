import assert from 'node:assert/strict';
import {mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {test} from 'node:test';
import {prepareRelease} from './prepare-release.mjs';

function fixture(action) {
  const root = mkdtempSync(join(tmpdir(), 'once-release-'));
  try {
    mkdirSync(join(root, 'namastack-once-examples/gradle'), {recursive: true});
    writeFileSync(join(root, 'build.gradle.kts'), '    version = "0.1.0" + if (!isRelease) "-SNAPSHOT" else ""\n');
    writeFileSync(join(root, 'namastack-once-examples/gradle/libs.versions.toml'), 'namastackOnce = "0.1.0-SNAPSHOT"\n');
    action(root);
  } finally {
    rmSync(root, {recursive: true, force: true});
  }
}

test('prepares project and example versions together', () => fixture(root => {
  prepareRelease('0.2.0-RC1', root);
  assert.match(readFileSync(join(root, 'build.gradle.kts'), 'utf8'), /version = "0.2.0-RC1"/);
  assert.match(readFileSync(join(root, 'namastack-once-examples/gradle/libs.versions.toml'), 'utf8'), /namastackOnce = "0.2.0-RC1-SNAPSHOT"/);
}));

test('rejects lower and invalid versions without changing files', () => fixture(root => {
  const file = join(root, 'build.gradle.kts');
  const before = readFileSync(file, 'utf8');
  assert.throws(() => prepareRelease('0.0.9', root));
  assert.throws(() => prepareRelease('0.2.0-SNAPSHOT', root));
  assert.equal(readFileSync(file, 'utf8'), before);
}));
