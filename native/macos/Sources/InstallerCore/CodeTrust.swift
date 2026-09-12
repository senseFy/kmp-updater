import Foundation
import Security

public protocol CodeTrust {
    func validateScope(_ request: InstallRequest) throws
    func validate(_ path: URL, teamId: String) throws
    func assess(_ application: URL) throws
}

/// The only trust policy linked by the production helper entry point.
public struct DeveloperIDTrust: CodeTrust {
    public init() {}
    public func validateScope(_ request: InstallRequest) throws {
        guard request.teamId.range(of: "^[A-Z0-9]{10}$", options: .regularExpression) != nil else {
            throw InstallerFailure("Invalid Developer ID publisher")
        }
    }
    public func assess(_ application: URL) throws {
        _ = try SystemCommand.run(
            "/usr/sbin/spctl", ["--assess", "--type", "execute", application.path], timeout: 90)
    }
    public func validate(_ path: URL, teamId: String) throws {
        var code: SecStaticCode?
        guard SecStaticCodeCreateWithPath(path as CFURL, [], &code) == errSecSuccess, let code
        else {
            throw InstallerFailure("Cannot read code signature")
        }
        // Developer ID Application certificate, Apple trust anchor and configured publisher.
        let expression =
            "anchor apple generic and certificate 1[field.1.2.840.113635.100.6.2.6] exists and certificate leaf[field.1.2.840.113635.100.6.1.13] exists and certificate leaf[subject.OU] = \"\(teamId)\""
        var requirement: SecRequirement?
        guard
            SecRequirementCreateWithString(expression as CFString, [], &requirement)
                == errSecSuccess
        else {
            throw InstallerFailure("Invalid code signing requirement")
        }
        let flags = SecCSFlags(
            rawValue: kSecCSStrictValidate | kSecCSCheckAllArchitectures | kSecCSCheckNestedCode)
        guard SecStaticCodeCheckValidity(code, flags, requirement) == errSecSuccess else {
            throw InstallerFailure("Invalid Developer ID signature or publisher")
        }
    }

}
