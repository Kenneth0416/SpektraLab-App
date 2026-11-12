import type { Locale } from '../i18n/translations';

export const coffeeCopy: Record<Locale, {
  title: string;
  eyebrow: string;
  lead: string;
  amount: string;
  wechat: string;
  alipay: string;
  wechatHint: string;
  scan: string;
  refuse: string;
  thanksTitle: string;
  thanksLead: string;
  thanksAction: string;
}> = {
  'zh-Hant': {
    title: '請我喝一杯',
    eyebrow: '下載之前 · 可選',
    lead: '銀鹽工坊可以免費下載。如果你覺得這間暗房值得留下，可以用 9.90 請我喝一杯。',
    amount: '¥9.90',
    wechat: '微信支付',
    alipay: '支付寶',
    wechatHint: '微信支付個人收款碼只能在微信裡打開。若按鈕沒有反應，請改用微信掃描二維碼。',
    scan: '用手機掃描對應的二維碼',
    refuse: '不用了，直接下載',
    thanksTitle: '感謝支持',
    thanksLead: '如果已經完成支付，接下來就進入下載。這頁無法向微信或支付寶核對是否已付款。',
    thanksAction: '進入下載',
  },
  'zh-Hans': {
    title: '请我喝一杯',
    eyebrow: '下载之前 · 可选',
    lead: '银盐工坊可以免费下载。如果你觉得这间暗房值得留下，可以用 9.90 请我喝一杯。',
    amount: '¥9.90',
    wechat: '微信支付',
    alipay: '支付宝',
    wechatHint: '微信支付个人收款码只能在微信里打开。若按钮没有反应，请改用微信扫描二维码。',
    scan: '用手机扫描对应的二维码',
    refuse: '不用了，直接下载',
    thanksTitle: '感谢支持',
    thanksLead: '如果已经完成支付，接下来就进入下载。此页无法向微信或支付宝核对是否已付款。',
    thanksAction: '进入下载',
  },
  en: {
    title: 'Buy me a coffee',
    eyebrow: 'Before download · optional',
    lead: 'Silvercam is free to download. If the darkroom is worth keeping, 9.90 is a coffee.',
    amount: '¥9.90',
    wechat: 'WeChat Pay',
    alipay: 'Alipay',
    wechatHint: 'This personal WeChat code opens inside WeChat. If the button does nothing, scan the code with WeChat.',
    scan: 'Scan the matching code with your phone',
    refuse: 'No thanks, download anyway',
    thanksTitle: 'Thank you',
    thanksLead: 'If the payment went through, the download is next. This page cannot confirm the payment with WeChat or Alipay.',
    thanksAction: 'Continue to download',
  },
};
