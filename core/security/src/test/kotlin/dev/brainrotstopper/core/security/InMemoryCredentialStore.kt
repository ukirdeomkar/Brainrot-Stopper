package dev.brainrotstopper.core.security

/** Test double for [CredentialStore]. Never used in the app. */
class InMemoryCredentialStore : CredentialStore {

    var credential: PasswordCredential? = null
        private set

    var writes: Int = 0
        private set

    override fun read(): PasswordCredential? = credential

    override fun write(credential: PasswordCredential) {
        this.credential = credential
        writes++
    }

    override fun clear() {
        credential = null
    }
}
