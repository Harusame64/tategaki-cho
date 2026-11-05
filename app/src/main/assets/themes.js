// The reader's themes and fonts, shared by reader.html and yomisashi.html. ReaderActivity.themeColors keeps the
// same bg, fg, sub and line for the app's own screens.
// sub (secondary text) is 4.5:1 on bg and card; edge (a button's border) 3:1 on card; line is a quiet rule.
var THEMES={
  kinari:{bg:'#fbf8f1',fg:'#222',sub:'#6b6b6b',card:'#faf8f3',line:'#e4e0d6',edge:'#8f8b81',link:'#1a5fb4'},
  white:{bg:'#ffffff',fg:'#111',sub:'#6b6b6b',card:'#ffffff',line:'#e2e2e2',edge:'#8f8f8f',link:'#1a5fb4'},
  sepia:{bg:'#f1e7d0',fg:'#4a3b2a',sub:'#6e5f48',card:'#efe4cb',line:'#d9cbaa',edge:'#8a7a5e',link:'#7a4b12'},
  dark:{bg:'#1f1f1f',fg:'#d6d2c8',sub:'#9a978f',card:'#2a2a2a',line:'#3a3a3a',edge:'#7a776f',link:'#8ab4f8'},
  black:{bg:'#000000',fg:'#a8a49a',sub:'#8a867c',card:'#121212',line:'#2a2a2a',edge:'#66635c',link:'#8ab4f8'}};
// Two fonts the phone has, two bundled with the app.
var FONTS={serif:{n:'明朝',f:'"Noto Serif CJK JP","Noto Serif JP",serif'},sans:{n:'ゴシック',f:'"Noto Sans CJK JP","Noto Sans JP",sans-serif'},
  bizud:{n:'BIZ UD明朝',f:'"BIZ UDMincho",serif'},shippori:{n:'しっぽり明朝',f:'"Shippori Mincho",serif'}};
