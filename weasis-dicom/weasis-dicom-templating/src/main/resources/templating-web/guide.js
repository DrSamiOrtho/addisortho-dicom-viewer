// Landmark guide for the templating page (11 Oct 2026): what to click, in one or two sentences, with a small schematic
// of a standard film (patient's right on the image left) and the point to click marked in orange.
// Schematics are drawn here as SVG (no images), so they need no files and stay sharp at any size.

const ORANGE = '#ff8a1f';
const bone = 'fill="#e9efed" stroke="#034537" stroke-width="1.2"';

// AP pelvis with both hips (patient's right on the image left). side: the operated side ('R' or 'L'); what: the
// landmark to mark. One hip is drawn for the patient's right and mirrored for the left.
function pelvis(side, what, mirrored = false) {   // mirrored: the patient's right on the image right
  const X = (x, m) => (m > 0 ? x : 200 - x);
  const half = m => {
    const x = v => X(v, m);
    return `<path d="M${x(80)},22 C${x(70)},8 ${x(40)},4 ${x(20)},12 C${x(12)},18 ${x(14)},32 ${x(24)},44 L${x(33)},48 C${x(40)},44 ${x(56)},44 ${x(61)},58 L${x(64)},64 L${x(90)},70 C${x(96)},74 ${x(97)},80 ${x(96)},86 C${x(86)},96 ${x(70)},100 ${x(60)},96 C${x(54)},90 ${x(54)},78 ${x(58)},70 C${x(60)},62 ${x(66)},50 ${x(80)},40 Z" ${bone}/>`
      + `<ellipse cx="${x(76)}" cy="83" rx="8" ry="10" fill="#fff" stroke="#034537" stroke-width="1"/>`
      + `<path d="M${x(63)},62 C${x(63)},75 ${x(69)},75 ${x(69)},62" fill="none" stroke="#034537" stroke-width="1.4"/>`
      + `<path d="M${x(28)},63 C${x(24)},70 ${x(26)},78 ${x(29)},84 L${x(34)},122 L${x(46)},122 L${x(46)},97 C${x(47)},94 ${x(51)},92 ${x(50)},88 C${x(49)},85 ${x(47)},83 ${x(50)},77 L${x(55)},68 L${x(44)},61 L${x(36)},64 C${x(33)},61 ${x(30)},61 ${x(28)},63 Z" ${bone}/>`
      + `<circle cx="${x(48)}" cy="58" r="10.5" ${bone}/>`
      + `<path d="M${x(33)},48 C${x(36)},40 ${x(56)},40 ${x(61)},56" fill="none" stroke="#034537" stroke-width="2"/>`;
  };
  const sx = (side === 'R') !== !!mirrored ? 1 : -1, P = (x, y) => [X(x, sx), y];
  const mark = ([x, y]) => `<circle cx="${x}" cy="${y}" r="4" fill="${ORANGE}" stroke="#000" stroke-width="1"/>`;
  let mk = '';
  if (what === 'td') mk = mark(P(66, 72));
  else if (what === 'lt') mk = mark(P(50.5, 89));
  else if (what === 'head') mk = mark(P(48, 47.5)) + mark(P(37.6, 59.5)) + mark(P(55.5, 65.5));
  else if (what === 'canal') { const a = P(38, 96), b = P(39.5, 118); mk = `<line x1="${a[0]}" y1="${a[1]}" x2="${b[0]}" y2="${b[1]}" stroke="${ORANGE}" stroke-width="2" stroke-dasharray="3 2"/>` + mark(a) + mark(b); }
  else if (what === 'ac') mk = mark(P(33, 47));
  return `<svg viewBox="0 0 200 124" width="200" height="124" role="img" aria-label="where to click">`
    + `<rect width="200" height="124" fill="#fff"/>` + half(1) + half(-1)
    + `<path d="M84,18 C92,14 108,14 116,18 L111,56 C106,62 94,62 89,56 Z" ${bone}/>`
    + `<text x="3" y="120" font-size="9" fill="#456">${mirrored ? 'L' : 'R'}</text><text x="191" y="120" font-size="9" fill="#456">${mirrored ? 'R' : 'L'}</text>` + mk + `</svg>`;
}

// AP knee (and the ends of a long-leg film): femur above, tibia below.
function kneeAP(what) {
  const mark = (x, y) => `<circle cx="${x}" cy="${y}" r="4.2" fill="${ORANGE}" stroke="#000" stroke-width="1"/>`;
  let mk = '';
  if (what === 'knee') mk = mark(100, 58);
  else if (what === 'condMed') mk = mark(76, 66);
  else if (what === 'condLat') mk = mark(124, 66);
  else if (what === 'platMed') mk = mark(80, 72);
  else if (what === 'platLat') mk = mark(120, 72);
  else if (what === 'femShaft') mk = `<line x1="101" y1="4" x2="100" y2="40" stroke="${ORANGE}" stroke-width="2" stroke-dasharray="3 2"/>` + mark(101, 8) + mark(100, 36);
  else if (what === 'tibShaft') mk = `<line x1="100" y1="84" x2="100" y2="118" stroke="${ORANGE}" stroke-width="2" stroke-dasharray="3 2"/>` + mark(100, 88) + mark(100, 114);
  return `<svg viewBox="0 0 200 122" width="200" height="122" role="img" aria-label="where to click"><rect width="200" height="122" fill="#fff"/>`
    + `<path d="M88,0 L112,0 L114,34 C130,40 136,52 132,62 C128,70 112,68 106,62 L100,56 L94,62 C88,68 72,70 68,62 C64,52 70,40 86,34 Z" ${bone}/>`
    + `<path d="M70,72 C80,68 92,70 98,68 L100,64 L102,68 C108,70 120,68 130,72 L126,82 C116,86 110,88 108,94 L108,122 L92,122 L92,94 C90,88 84,86 74,82 Z" ${bone}/>`
    + `<text x="4" y="119" font-size="8" fill="#456">medial / lateral by the film's side</text>` + mk + `</svg>`;
}

function longLeg(what) {   // femoral head at the top, ankle at the bottom
  const mark = (x, y) => `<circle cx="${x}" cy="${y}" r="4.2" fill="${ORANGE}" stroke="#000" stroke-width="1"/>`;
  const mk = what === 'hip' ? mark(92, 12) + mark(104, 10) + mark(108, 22) : mark(102, 111);   // the ankle: the centre of the talus (Sami, 23 Oct)
  return `<svg viewBox="0 0 200 122" width="200" height="122" role="img" aria-label="where to click"><rect width="200" height="122" fill="#fff"/>`
    + `<circle cx="100" cy="16" r="9" ${bone}/><path d="M104,22 L116,30 L110,58 L104,58 L104,32 Z" ${bone}/>`
    + `<path d="M98,62 L114,62 L112,104 L104,104 Z" ${bone}/><path d="M90,106 L114,106 L112,116 L92,116 Z" ${bone}/>`
    + `<text x="4" y="12" font-size="8" fill="#456">${what === 'hip' ? 'top of the long-leg film' : 'bottom of the long-leg film'}</text>` + mk + `</svg>`;
}

function kneeLat(what) {   // anterior to the right
  const mark = (x, y) => `<circle cx="${x}" cy="${y}" r="4.2" fill="${ORANGE}" stroke="#000" stroke-width="1"/>`;
  let mk = '';
  if (what === 'antCortex') mk = `<line x1="117" y1="4" x2="119" y2="40" stroke="${ORANGE}" stroke-width="2" stroke-dasharray="3 2"/>` + mark(117, 8) + mark(118.5, 34);
  else if (what === 'postCond') mk = mark(70, 52);
  else if (what === 'platAnt') mk = mark(124, 72);
  else if (what === 'platPost') mk = mark(80, 74);
  else if (what === 'tibAxis') mk = `<line x1="103" y1="84" x2="101" y2="118" stroke="${ORANGE}" stroke-width="2" stroke-dasharray="3 2"/>` + mark(103, 88) + mark(101, 114);
  return `<svg viewBox="0 0 200 122" width="200" height="122" role="img" aria-label="where to click"><rect width="200" height="122" fill="#fff"/>`
    + `<path d="M96,0 L118,0 L120,40 C130,46 132,58 124,66 C110,72 84,70 74,62 C64,54 70,42 86,40 L96,30 Z" ${bone}/>`
    + `<path d="M76,74 L126,70 L128,78 C120,84 112,86 110,94 L108,122 L94,122 L94,94 C90,86 80,82 76,74 Z" ${bone}/>`
    + `<text x="150" y="12" font-size="8" fill="#456">front →</text>` + mk + `</svg>`;
}

// guide[what] = {text, svg(side)}; `what` is the landmark key without its side letter
export const GUIDE = {
  td: { text: 'The teardrop is the U-shaped line at the bottom of the acetabular floor, beside the obturator foramen. Click its lowest point. The line through both teardrops is the reference for leg length and cup angle.', svg: (s, m) => pelvis(s, 'td', m) },
  lt: { text: 'The lesser trochanter is the bony bump on the inner side of the femur, below the neck. Click its tip (its most prominent point). Used for leg length.', svg: (s, m) => pelvis(s, 'lt', m) },
  head: { text: 'Click three points spread round the edge of the femoral head: top, outer side and inner side. The page fits a circle; its centre is the centre of rotation.', svg: (s, m) => pelvis(s, 'head', m) },
  canal: { text: 'Click the middle of the femoral canal twice, down the shaft: first just below the lesser trochanter, then 10 to 15 cm lower. The page then centres the line between the inner edges of the cortex.', svg: (s, m) => pelvis(s, 'canal', m) },
  ac: { text: 'Optional. The outer end of the acetabular roof, where the dense roof line (sourcil) ends laterally. Used to size the cup by its cover.', svg: (s, m) => pelvis(s, 'ac', m) },
  hip: { text: 'On a long-leg film: three points round the femoral head. Its centre starts the femoral mechanical axis.', svg: () => longLeg('hip') },
  femShaft: { text: 'Two points on the middle of the femoral shaft, high then low: the anatomical axis.', svg: () => kneeAP('femShaft') },
  knee: { text: 'The knee centre: the top of the intercondylar notch, between the tibial spines.', svg: () => kneeAP('knee') },
  condMed: { text: 'The lowest point of the medial femoral condyle (its joint surface).', svg: () => kneeAP('condMed') },
  condLat: { text: 'The lowest point of the lateral femoral condyle (its joint surface).', svg: () => kneeAP('condLat') },
  platMed: { text: 'The middle of the medial tibial plateau, on the joint surface.', svg: () => kneeAP('platMed') },
  platLat: { text: 'The middle of the lateral tibial plateau, on the joint surface.', svg: () => kneeAP('platLat') },
  ankle: { text: 'On a long-leg film: the centre of the talus at the ankle joint.', svg: () => longLeg('ankle') },
  tibShaft: { text: 'Short knee film without the ankle: two points on the middle of the tibial shaft, high then low.', svg: () => kneeAP('tibShaft') },
  antCortex: { text: 'Two points along the front surface of the femoral shaft just above the trochlea.', svg: () => kneeLat('antCortex') },
  postCond: { text: 'The back edge of the posterior condyles (midway between the two outlines if they do not overlap).', svg: () => kneeLat('postCond') },
  platAnt: { text: 'The front edge of the tibial plateau joint surface.', svg: () => kneeLat('platAnt') },
  platPost: { text: 'The back edge of the tibial plateau joint surface.', svg: () => kneeLat('platPost') },
  tibAxis: { text: 'Two points on the middle of the tibial shaft, high then low.', svg: () => kneeLat('tibAxis') },
};
