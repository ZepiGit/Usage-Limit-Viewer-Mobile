# Z.ai GLM Coding Plan

Add Z.ai in Accounts, choose the region that issued your key and paste a Coding Plan API key. International uses `https://api.z.ai`; China uses `https://open.bigmodel.cn`. The services have separate accounts and keys.

The app checks the key before saving it in Android's encrypted credential store or iOS Keychain. There is no refresh grant. Usage is `GET <host>/api/monitor/usage/quota/limit` with bearer authorization and `Accept: application/json`. A body reporting `code: 401` rejects the key even if HTTP status is 200.

## Reading quota

`TOKENS_LIMIT`, `TIME_LIMIT` and `CREDIT_LIMIT` become quota windows. `percentage` is percent consumed when supplied; otherwise consumption is `currentValue / usage`, where `usage` is the capacity. Missing or zero capacity and invalid percentages stay unknown.

Duration comes from `number` and `unit`: 1 means days, 3 hours, 5 minutes and 6 weeks. The monthly `TIME_LIMIT` convention is unit 5, number 1, interpreted as 30 days. Unknown units keep unknown duration. `nextResetTime` is epoch milliseconds and can be absent at zero use. `data.level` supplies the plan label.

A one-way key digest identifies the account because this response supplies no user ID. Region is part of that identity, so connecting one region cannot overwrite the other. Pasting the same key updates the account; rotating it creates a new one. Removal deletes the local key; revoke it in the issuing console. Pay-as-you-go cash balances are separate from these plan windows.
