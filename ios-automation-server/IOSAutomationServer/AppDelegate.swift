import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        let viewController = UIViewController()
        viewController.view.backgroundColor = .systemBackground
        let label = UILabel()
        label.text = "iOS Automation Server\nHost app for XCUITest automation"
        label.numberOfLines = 0
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        viewController.view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: viewController.view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: viewController.view.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: viewController.view.leadingAnchor, constant: 20),
            label.trailingAnchor.constraint(lessThanOrEqualTo: viewController.view.trailingAnchor, constant: -20)
        ])
        window?.rootViewController = viewController
        window?.makeKeyAndVisible()
        return true
    }
}
