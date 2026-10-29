# Firestore rules tests

With Node.js and Java installed, run `npm install` and `npm test` from this directory. The Firebase CLI starts a local Firestore emulator with the `demo-rcrm` project; these tests do not contact production. The tests cover ownership, child identity, and atomic creation of a lead and its first reminder. Deploying the rules is a separate operation.
