@_exported import AppleShims
import Foundation

public struct NotificationPublisher {
    public func receive(on scheduler: RunLoop) -> Self { self }
}

extension NotificationCenter {
    public func publisher(for name: Notification.Name) -> NotificationPublisher { NotificationPublisher() }
}
