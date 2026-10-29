# Cross-device verification

Use a separate development Firebase project with these rules deployed and one disposable signed-in account on both devices. Never use a customer contact. Check the Firestore documents as well as both app screens.

1. On iOS, create a uniquely named contact with multiple categories, sales and recruit stages, notes, and a future follow-up. Confirm the lead and its PENDING `next_actions` document have the correct owner, lead ID and matching date (lead timestamp and action epoch milliseconds).
2. On Android, refresh and verify every field and the reminder. Edit the categories, stages, notes and reminder there; reopen on iOS and verify each change.
3. On iOS, edit the same fields and move the follow-up. Verify Android shows the changes and the same pending action due time. Add another Android reminder, then move and clear the earliest on iOS; verify the other remains pending and the lead date equals the earliest remaining reminder.
4. Open the same contact for editing on both devices. Save different fields on each; verify both survive. Repeat with the same field and with follow-up changes, recording any conflict or lost edit. Test a timestamp and an epoch-millisecond legacy date.
5. Archive, restore, and permanently delete only once those features are implemented. Verify archived records are hidden on both platforms and restored with history; verify permanent deletion removes both `next_actions` and `activity_records` before the lead disappears. The current iOS deletion leaves subcollections behind, so do not use it as a cleanup test.
6. With a second development account, verify it cannot read, edit, or create children under the first account's lead. Run the rules emulator tests as well.
