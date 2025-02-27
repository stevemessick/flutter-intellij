// Copyright 2017 The Chromium Authors. All rights reserved. Use of this source
// code is governed by a BSD-style license that can be found in the LICENSE file.

import 'dart:async';
import 'dart:io';

import 'package:plugin_tool/plugin.dart';
import 'package:plugin_tool/runner.dart';
import 'package:plugin_tool/util.dart';
import 'package:test/test.dart';

void main() {
  group("create", () {
    test('build', () {
      expect(AntBuildCommand(BuildCommandRunner()).name, "build");
    });

    test('make', () {
      expect(GradleBuildCommand(BuildCommandRunner()).name, "make");
    });

    test('test', () {
      expect(TestCommand(BuildCommandRunner()).name, "test");
    });

    test('deploy', () {
      expect(DeployCommand(BuildCommandRunner()).name, "deploy");
    });

    test('generate', () {
      expect(GenerateCommand(BuildCommandRunner()).name, "generate");
    });
  });

  group("spec", () {
    test('build', () async {
      var runner = makeTestRunner();
      await runner.run(["-r=19", "-d../..", "build"]).whenComplete(() {
        var specs = (runner.commands['build'] as ProductCommand).specs;
        expect(specs, isNotNull);
        expect(
            specs.map((spec) => spec.ideaProduct).toList(),
            orderedEquals([
              'android-studio',
              'android-studio',
              'android-studio',
              'ideaIC'
            ]));
      });
    });

    test('test', () async {
      var runner = makeTestRunner();
      await runner.run(["-r=19", "-d../..", "test"]).whenComplete(() {
        var specs = (runner.commands['test'] as ProductCommand).specs;
        expect(specs, isNotNull);
        expect(
            specs.map((spec) => spec.ideaProduct).toList(),
            orderedEquals([
              'android-studio',
              'android-studio',
              'android-studio',
              'ideaIC'
            ]));
      });
    });

    test('deploy', () async {
      var runner = makeTestRunner();
      await runner.run(["-r19", "-d../..", "deploy"]).whenComplete(() {
        var specs = (runner.commands['deploy'] as ProductCommand).specs;
        expect(specs, isNotNull);
        expect(
            specs.map((spec) => spec.ideaProduct).toList(),
            orderedEquals([
              'android-studio',
              'android-studio',
              'android-studio',
              'ideaIC'
            ]));
      });
    });
  });

  group('release', () {
    test('simple', () async {
      var runner = makeTestRunner();
      late TestDeployCommand cmd;
      await runner.run(["-r19", "-d../..", "deploy"]).whenComplete(() {
        cmd = (runner.commands['deploy'] as TestDeployCommand);
      });
      expect(cmd.isReleaseValid, true);
    });

    test('minor', () async {
      var runner = makeTestRunner();
      late TestDeployCommand cmd;
      await runner.run(["-r19.2", "-d../..", "deploy"]).whenComplete(() {
        cmd = (runner.commands['deploy'] as TestDeployCommand);
      });
      expect(cmd.isReleaseValid, true);
    });

    test('patch invalid', () async {
      var runner = makeTestRunner();
      late TestDeployCommand cmd;
      await runner.run(["-r19.2.1", "-d../..", "deploy"]).whenComplete(() {
        cmd = (runner.commands['deploy'] as TestDeployCommand);
      });
      expect(cmd.isReleaseValid, false);
    });

    test('non-numeric', () async {
      var runner = makeTestRunner();
      late TestDeployCommand cmd;
      await runner.run(["-rx19.2", "-d../..", "deploy"]).whenComplete(() {
        cmd = (runner.commands['deploy'] as TestDeployCommand);
      });
      expect(cmd.isReleaseValid, false);
    });
  });

  group('deploy', () {
    test('clean', () async {
      var dir = Directory.current;
      var runner = makeTestRunner();
      await runner.run([
        "-r=19",
        "-d../..",
        "deploy",
        "--no-as",
        "--no-ij"
      ]).whenComplete(() {
        expect(Directory.current.path, equals(dir.path));
      });
    });

    test('without --release', () async {
      var runner = makeTestRunner();
      late TestDeployCommand cmd;
      await runner.run(["-d../..", "deploy"]).whenComplete(() {
        cmd = (runner.commands['deploy'] as TestDeployCommand);
      });
      expect(cmd.paths, orderedEquals([]));
    });

    test('release paths', () async {
      var runner = makeTestRunner();
      late TestDeployCommand cmd;
      await runner.run(["--release=19", "-d../..", "deploy"]).whenComplete(() {
        cmd = (runner.commands['deploy'] as TestDeployCommand);
      });
      var specs = cmd.specs.where((s) => s.isStableChannel).toList();
      expect(cmd.paths.length, specs.length);
    });
  });

  group('build', () {
    test('plugin.xml', () async {
      var runner = makeTestRunner();
      late TestBuildCommand cmd;
      await runner.run(["-d../..", "build"]).whenComplete(() {
        cmd = (runner.commands['build'] as TestBuildCommand);
      });
      var spec = cmd.specs[0];
      await removeAll('../../build/classes');
      await genPluginFiles(spec, 'build/classes');
      var file = File("../../build/classes/META-INF/plugin.xml");
      expect(file.existsSync(), isTrue);
      var content = file.readAsStringSync();
      expect(content.length, greaterThan(10000));
      var loc = content.indexOf('@');
      expect(loc, -1);
    });

    test('only-version', () async {
      ProductCommand command =
          makeTestRunner().commands['build'] as ProductCommand;
      var results = command.argParser.parse(['--only-version=2018.1']);
      expect(results['only-version'], '2018.1');
    });
  });

  group('ProductCommand', () {
    test('parses release', () async {
      var runner = makeTestRunner();
      late ProductCommand command;
      await runner.run(["-d../..", '-r22.0', "build"]).whenComplete(() {
        command = (runner.commands['build'] as ProductCommand);
      });
      expect(command.release, '22.0');
    });
    test('parses release partial number', () async {
      var runner = makeTestRunner();
      late ProductCommand command;
      await runner.run(["-d../..", '-r22', "build"]).whenComplete(() {
        command = (runner.commands['build'] as ProductCommand);
      });
      expect(command.release, '22.0');
    });

    test('isReleaseValid', () async {
      var runner = makeTestRunner();
      late ProductCommand command;
      await runner.run(["-d../..", '-r22.0', "build"]).whenComplete(() {
        command = (runner.commands['build'] as ProductCommand);
      });
      expect(command.isReleaseValid, true);
    });
    test('isReleaseValid partial version', () async {
      var runner = makeTestRunner();
      late ProductCommand command;
      await runner.run(["-d../..", '-r22', "build"]).whenComplete(() {
        command = (runner.commands['build'] as ProductCommand);
      });
      expect(command.isReleaseValid, true);
    });
    test('isReleaseValid bad version', () async {
      var runner = makeTestRunner();
      late ProductCommand command;
      await runner.run(["-d../..", '-r22.0.0', "build"]).whenComplete(() {
        command = (runner.commands['build'] as ProductCommand);
      });
      expect(command.isReleaseValid, false);
    });
  });
}

BuildCommandRunner makeTestRunner() {
  var runner = BuildCommandRunner();
  runner.addCommand(TestBuildCommand(runner));
  runner.addCommand(TestMakeCommand(runner));
  runner.addCommand(TestTestCommand(runner));
  runner.addCommand(TestDeployCommand(runner));
  runner.addCommand(TestGenCommand(runner));
  return runner;
}

class TestBuildCommand extends AntBuildCommand {
  TestBuildCommand(runner) : super(runner);

  @override
  bool get isTesting => true;

  @override
  Future<int> doit() async => Future(() => 0);
}

class TestMakeCommand extends GradleBuildCommand {
  TestMakeCommand(runner) : super(runner);

  @override
  bool get isTesting => true;

  @override
  Future<int> doit() async => Future(() => 0);
}

class TestDeployCommand extends DeployCommand {
  List<String> paths = <String>[];
  List<String> plugins = <String>[];

  TestDeployCommand(runner) : super(runner);

  @override
  bool get isTesting => true;

  String readTokenFile() {
    return "token";
  }

  @override
  void changeDirectory(Directory dir) {}

  @override
  Future<int> upload(
      String filePath, String pluginNumber, String token, String channel) {
    paths.add(filePath);
    plugins.add(pluginNumber);
    return Future(() => 0);
  }
}

class TestGenCommand extends GenerateCommand {
  TestGenCommand(runner) : super(runner);

  @override
  bool get isTesting => true;

  @override
  Future<int> doit() async => Future(() => 0);
}

class TestTestCommand extends TestCommand {
  TestTestCommand(runner) : super(runner);

  @override
  bool get isTesting => true;

  @override
  Future<int> doit() async => Future(() => 0);
}
