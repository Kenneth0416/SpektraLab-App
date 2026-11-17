import type { Locale } from '../i18n/translations';

export const coffeeCopy: Record<Locale, {
  title: string;
  eyebrow: string;
  lead: string;
  amount: string;
  wechat: string;
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
    scan: '使用微信掃描，或儲存圖片後從微信相簿辨識；請在微信確認 ¥9.90 金額。',
    refuse: '狠心拒絕，直接下載',
    thanksTitle: '感謝支持',
    thanksLead: '如果已經完成支付，接下來就進入下載。這頁無法向微信核對是否已付款。',
    thanksAction: '進入下載',
  },
  'zh-Hans': {
    title: '请我喝一杯',
    eyebrow: '下载之前 · 可选',
    lead: '银盐工坊可以免费下载。如果你觉得这间暗房值得留下，可以用 9.90 请我喝一杯。',
    amount: '¥9.90',
    wechat: '微信支付',
    scan: '使用微信扫描，或保存图片后从微信相册识别；请在微信确认 ¥9.90 金额。',
    refuse: '狠心拒绝，直接下载',
    thanksTitle: '感谢支持',
    thanksLead: '如果已经完成支付，接下来就进入下载。此页无法向微信核对是否已付款。',
    thanksAction: '进入下载',
  },
  en: {
    title: 'Buy me a coffee',
    eyebrow: 'Before download · optional',
    lead: 'Silvercam is free to download. If the darkroom is worth keeping, 9.90 is a coffee.',
    amount: '¥9.90',
    wechat: 'WeChat Pay',
    scan: 'Scan with WeChat, or save the image and select it from the WeChat scan album. Confirm the ¥9.90 amount in WeChat.',
    refuse: 'No thanks, download anyway',
    thanksTitle: 'Thank you',
    thanksLead: 'If the payment went through, the download is next. This page cannot confirm the payment with WeChat.',
    thanksAction: 'Continue to download',
  },
};
