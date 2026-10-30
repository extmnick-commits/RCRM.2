import { readFile } from 'node:fs/promises';
import { after, before, beforeEach, test } from 'node:test';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, writeBatch } from 'firebase/firestore';

let environment;
before(async () => {
  environment = await initializeTestEnvironment({
    projectId: 'demo-rcrm',
    firestore: { rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8') }
  });
});
beforeEach(async () => environment.clearFirestore());
after(async () => environment.cleanup());

const leadData = ownerId => ({ ownerId, name: 'Disposable' });
const actionData = (ownerId, leadId) => ({ ownerId, leadId, status: 'PENDING', dueTime: 1900000000000 });

test('an owner can atomically create a contact and its first reminder', async () => {
  const db = environment.authenticatedContext('alice').firestore();
  const batch = writeBatch(db);
  batch.set(doc(db, 'leads/lead1'), leadData('alice'));
  batch.set(doc(db, 'leads/lead1/next_actions/action1'), actionData('alice', 'lead1'));
  await assertSucceeds(batch.commit());
  await assertSucceeds(getDoc(doc(db, 'leads/lead1/next_actions/action1')));
});

test('another user cannot read or modify a contact or its reminders', async () => {
  await environment.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    await setDoc(doc(db, 'leads/lead1'), leadData('alice'));
    await setDoc(doc(db, 'leads/lead1/next_actions/action1'), actionData('alice', 'lead1'));
  });
  const db = environment.authenticatedContext('bob').firestore();
  await assertFails(getDoc(doc(db, 'leads/lead1')));
  await assertFails(updateDoc(doc(db, 'leads/lead1'), { name: 'Changed' }));
  await assertFails(getDoc(doc(db, 'leads/lead1/next_actions/action1')));
  await assertFails(setDoc(doc(db, 'leads/lead1/next_actions/action2'), actionData('bob', 'lead1')));
});

test('child ownership and parent identity cannot be forged', async () => {
  const db = environment.authenticatedContext('alice').firestore();
  await assertSucceeds(setDoc(doc(db, 'leads/lead1'), leadData('alice')));
  await assertFails(setDoc(doc(db, 'leads/lead1/next_actions/wrong-owner'), actionData('bob', 'lead1')));
  await assertFails(setDoc(doc(db, 'leads/lead1/next_actions/wrong-parent'), actionData('alice', 'other')));
  await assertFails(updateDoc(doc(db, 'leads/lead1'), { ownerId: 'bob' }));
});

test('a batch cannot create a reminder for another owner or a missing contact', async () => {
  await environment.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), 'leads/alices-lead'), leadData('alice'));
  });
  const db = environment.authenticatedContext('bob').firestore();
  const other = writeBatch(db);
  other.set(doc(db, 'leads/alices-lead/next_actions/action'), actionData('bob', 'alices-lead'));
  await assertFails(other.commit());
  await assertFails(setDoc(doc(db, 'leads/missing/next_actions/action'), actionData('bob', 'missing')));
});

test('an owner can create and update a reminder on an existing contact', async () => {
  const db = environment.authenticatedContext('alice').firestore();
  await assertSucceeds(setDoc(doc(db, 'leads/lead1'), leadData('alice')));
  const action = doc(db, 'leads/lead1/next_actions/action1');
  await assertSucceeds(setDoc(action, actionData('alice', 'lead1')));
  await assertSucceeds(updateDoc(action, { dueTime: 1900000060000 }));
  await assertFails(updateDoc(action, { ownerId: 'bob' }));
});
