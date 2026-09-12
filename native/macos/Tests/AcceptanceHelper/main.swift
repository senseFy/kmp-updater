// Local acceptance executable. This target is never embedded by production packaging.
import Darwin
import Foundation
import InstallerCore
import Security

private struct FixtureTrust: CodeTrust {
    let root: URL

    init() throws {
        var app = URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath()
        while app.pathExtension != "app", app.path != "/" { app.deleteLastPathComponent() }
        root = app.deletingLastPathComponent()
        guard app.lastPathComponent == "Updater Lab.app",
            root.deletingLastPathComponent().lastPathComponent == "runs",
            root.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent
                == "acceptance",
            try String(
                contentsOf: root.appendingPathComponent(".updater-lab-fixture"), encoding: .utf8)
                == "kmp-updater-acceptance-v1\n"
        else {
            throw InstallerFailure(
                "Fixture helper is restricted to an Updater Lab acceptance directory")
        }
    }

    func validateScope(_ request: InstallRequest) throws {
        guard request.appId == "saien.updater.lab",
            request.target == root.appendingPathComponent("Updater Lab.app").path,
            URL(fileURLWithPath: request.archive).standardizedFileURL.path.hasPrefix(
                root.path + "/")
        else { throw InstallerFailure("Fixture request escaped its test application") }
    }

    func validate(_ path: URL, teamId: String) throws {
        guard path.standardizedFileURL.path.hasPrefix(root.path + "/") else {
            throw InstallerFailure("Fixture code escaped test directory")
        }
        if path.pathExtension == "app" {
            let data = try Data(contentsOf: path.appendingPathComponent("Contents/Info.plist"))
            let info =
                try PropertyListSerialization.propertyList(from: data, format: nil)
                as? [String: Any]
            guard info?["CFBundleIdentifier"] as? String == "saien.updater.lab",
                info?["KMPUpdaterAcceptanceFixture"] as? Bool == true
            else { throw InstallerFailure("Expected an explicitly marked fixture app") }
        }
        var code: SecStaticCode?
        guard SecStaticCodeCreateWithPath(path as CFURL, [], &code) == errSecSuccess, let code
        else { throw InstallerFailure("Fixture has no code signature") }
        let flags = SecCSFlags(
            rawValue: kSecCSStrictValidate | kSecCSCheckAllArchitectures | kSecCSCheckNestedCode)
        guard SecStaticCodeCheckValidity(code, flags, nil) == errSecSuccess else {
            throw InstallerFailure("Fixture code signature is invalid")
        }
    }

    func assess(_ application: URL) throws {
        // Local ad-hoc fixtures are not notarized. Their complete code seals are still verified.
        try validate(application, teamId: "FIXTURE000")
    }
}

do {
    guard CommandLine.arguments.count == 3 else {
        throw InstallerFailure("Expected command and path")
    }
    let trust = try FixtureTrust()
    if CommandLine.arguments[1] == "install" {
        try Data(String(getpid()).utf8).write(
            to: trust.root.appendingPathComponent("helper-pid"), options: .atomic)
    }
    let configData = try Data(contentsOf: trust.root.appendingPathComponent("scenario.json"))
    let config = try JSONSerialization.jsonObject(with: configData) as? [String: Any] ?? [:]
    let fault = config["fault"] as? String ?? ""
    let installer = Installer(trust: trust, exitTimeout: fault == "exit-timeout" ? 2 : 120) {
        phase in
        let output = trust.root.appendingPathComponent("helper-\(phase)")
        try Data(phase.utf8).write(to: output, options: .atomic)
        if fault == "crash-\(phase)" { _exit(77) }
        if fault == "fail-\(phase)" { throw InstallerFailure("Injected failure at \(phase)") }
    }
    let path = URL(fileURLWithPath: CommandLine.arguments[2])
    switch CommandLine.arguments[1] {
    case "prepare": print(try installer.prepare(requestFile: path).path)
    case "install": try installer.install(directory: path)
    case "status": print(try installer.status(directory: path))
    case "discard": try installer.discard(directory: path)
    case "confirm": try installer.confirm(directory: path)
    default: throw InstallerFailure("Unknown fixture command")
    }
} catch {
    FileHandle.standardError.write(Data("\(error)\n".utf8))
    exit(1)
}
