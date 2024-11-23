package io.seqera.tower.crypto

import spock.lang.Specification

import java.util.concurrent.ThreadLocalRandom

class AsymmetricCipherTest extends Specification{

    def 'should decode keys'() {
        given: 'a cipher'
        def cipher = AsymmetricCipher.getInstance()
        and: 'a keypair generated with that cipher'
        def keypair = cipher.generateKeyPair()

        when: 'decoding the generated keys'
        def publicKey = cipher.decodePublicKey(keypair.public.getEncoded())
        def privateKey = cipher.decodePrivateKey(keypair.private.getEncoded())

        then: 'should give back the original keys'
        publicKey == keypair.public
        privateKey == keypair.private
    }

    def 'should round trip data encrypted with public key'() {
        given: 'a cipher'
        def cipher = AsymmetricCipher.getInstance()

        and: 'a keypair generated with that cipher'
        def keypair = cipher.generateKeyPair()

        and: 'a payload to encrypt'
        byte[] data = randomData()

        and: 'the data encrypted with public key'
        def packet = cipher.encrypt(keypair.public, data)

        when: 'packet is decrypted with private key'
        def decrypted = cipher.decrypt(packet, keypair.private)

        then: 'the decrypted data should match'
        decrypted == data
    }


    def 'should round trip data encrypted with private key'() {
        given: 'a cipher'
        def cipher = AsymmetricCipher.getInstance()

        and: 'a keypair generated with that cipher'
        def keypair = cipher.generateKeyPair()

        and: 'a payload to encrypt'
        byte[] data = randomData()

        and: 'the data encrypted with private key'
        def packet = cipher.encrypt(keypair.private, data)

        when: 'packet is decrypted with public key'
        def decrypted = cipher.decrypt(packet, keypair.public)

        then: 'the decrypted data should match'
        decrypted == data
    }

    def 'should fail to decrypt data using the public key twice'() {
        given: 'a cipher'
        def cipher = AsymmetricCipher.getInstance()

        and: 'a keypair generated with that cipher'
        def keypair = cipher.generateKeyPair()

        and: 'a payload to encrypt'
        byte[] data = randomData()

        and: 'the data encrypted with public key'
        def packet = cipher.encrypt(keypair.public, data)

        when: 'decrypting with the public key'
        cipher.decrypt(packet, keypair.public)

        then: 'it should fail'
        def e = thrown(IllegalArgumentException)
        e.message == "packet uses public encryption"
    }

    def 'should fail to decrypt data using the private key twice'() {
        given: 'a cipher'
        def cipher = AsymmetricCipher.getInstance()

        and: 'a keypair generated with that cipher'
        def keypair = cipher.generateKeyPair()

        and: 'a payload to encrypt'
        byte[] data = randomData()

        and: 'the data encrypted with public key'
        def packet = cipher.encrypt(keypair.private, data)

        when: 'decrypting with the public key'
        cipher.decrypt(packet, keypair.private)

        then: 'it should fail'
        def e = thrown(IllegalArgumentException)
        e.message == "packet uses private encryption"
    }

    def 'should decrypt data with different ciphers'() {
        given: 'an encrypting cipher'
        def encryptor = AsymmetricCipher.getInstance()
        and: 'a decrypting cipher'
        def decrypter = AsymmetricCipher.getInstance()

        and: 'a keypair'
        def keypair = decrypter.generateKeyPair()


        and: 'a payload to encrypt'
        byte[] data = randomData()

        and: 'the data encrypted with encryptor'
        def packet = encryptor.encrypt(keypair.public, data)

        when: 'decrypting with the other cipher'
        def decrypted = decrypter.decrypt(packet, keypair.private)

        then: 'content should match'
        decrypted == data
    }



    def 'should fail decrypt with public key when used with invalid packets'() {
        given: 'a cipher'
        def cipher = AsymmetricCipher.getInstance()

        and: 'a keypair'
        def keypair = cipher.generateKeyPair()

        and: 'a tampered packet'
        def packet = new EncryptedPacket(usePublic: true, encryptedKey: new byte[100], encryptedData: new byte[1024])

        when: 'decrypting the packet'
        cipher.decrypt(packet, keypair.public)

        then: 'an exception should be thrown'
        thrown(Exception)
    }

    def 'should fail decrypt with private key when used with invalid packets'() {
        given: 'a cipjer'
        def cipher = AsymmetricCipher.getInstance()

        and: 'a keypair'
        def keypair = cipher.generateKeyPair()

        and: 'a tampered packet'
        def packet = new EncryptedPacket(usePublic: true, encryptedKey: new byte[100], encryptedData: new byte[1024])

        when: 'decrypting the packet'
        cipher.decrypt(packet, keypair.private)

        then: 'an exception should be thrown'
        thrown(Exception)
    }


    private byte[] randomData() {
        final rng = ThreadLocalRandom.current()
        def data = new byte[rng.nextInt(2)]
        rng.nextBytes(data)
        return data
    }
}
