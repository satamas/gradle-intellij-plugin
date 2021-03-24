package org.jetbrains.intellij

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

open class SigningExtension(objects: ObjectFactory) {
    val privateKeyProperty: Property<String> = objects.property(String::class.java)
    val certificateChainProperty: Property<String> = objects.property(String::class.java)
    val enabledProperty: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    var privateKey: String
        get() = privateKeyProperty.get()
        set(value) {
            privateKeyProperty.set(value)
        }
    var certificateChain: String
        get() = certificateChainProperty.get()
        set(value) {
            certificateChainProperty.set(value)
        }
    var enabled: Boolean
        get() = enabledProperty.get()
        set(value) {
            enabledProperty.set(value)
        }
}