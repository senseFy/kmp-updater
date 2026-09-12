import Foundation
import InstallerCore

do {
    guard CommandLine.arguments.count == 3 else {
        throw InstallerFailure("Usage: kmp-updater-helper prepare|install|status <path>")
    }
    let path = URL(fileURLWithPath: CommandLine.arguments[2])
    let installer = Installer()
    switch CommandLine.arguments[1] {
    case "prepare": print(try installer.prepare(requestFile: path).path)
    case "install": try installer.install(directory: path)
    case "status": print(try installer.status(directory: path))
    case "discard": try installer.discard(directory: path)
    case "confirm": try installer.confirm(directory: path)
    default: throw InstallerFailure("Unknown helper command")
    }
} catch {
    FileHandle.standardError.write(Data("\(error)\n".utf8))
    exit(1)
}
