# 💸 SpareChange

<p align="center">
  <b>Simple. Safe. Developer-first Google Play Billing.</b><br>
  Lightweight wrapper around Play Billing v8+
</p>

<p align="center">

![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Java](https://img.shields.io/badge/Java-17-blue)
![Billing](https://img.shields.io/badge/Play%20Billing-v8%2B-orange)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

</p>

---

## ✨ Overview

**SpareChange** removes the complexity of Google Play Billing and replaces it with a clean, predictable, and developer-friendly API.

No boilerplate. No lifecycle headaches. Just billing that works.

---

## 🚀 Why Use SpareChange?

- 🧠 No more billing state confusion  
- ⚡ Plug-and-play initialization  
- 💰 Real-time pricing from Play Store  
- 🔄 Automatic purchase handling  
- 📉📈 Built-in price change detection *(rare feature)*  
- 🔐 Ownership tracking done right  

---

## ✨ Features

- ⚡ **Easy setup & initialization**
- 💰 **Automatic ProductDetails loading**
- 📉📈 **Price change detection**
- 🔐 **Ownership tracking**
- 🔄 **Auto consume / acknowledge**
- 🧩 **Unified API**
- 📡 **Event listener system**
- 🪪 **Built-in License Manager**

---

## 🧱 Requirements

- **Java 17**
- Google Play Billing Library v8+

---

## 📦 Installation

### Gradle (JitPack)

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.rgocal:SpareChange:1'
}
```

---

## 🚀 Initialization

```java
BillingManager.init(context, consumables, oneTime, subs, true, true, true);
```

---

## 📜 License

MIT License
