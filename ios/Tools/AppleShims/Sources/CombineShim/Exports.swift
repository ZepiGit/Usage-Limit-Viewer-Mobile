@_exported import AppleShims
import Foundation

extension NotificationCenter {
    public func publisher(for name: Notification.Name) -> Notification.Name { name }
}
