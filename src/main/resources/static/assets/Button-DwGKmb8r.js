import"./chunk-DECur_0Z.js";import{t as e}from"./react-DYpzXrK8.js";import{u as t}from"./dist-DpcFxDWc.js";e();var n=t(),r={sm:`px-3 py-1.5 text-xs`,md:`px-4 py-2 text-sm`,lg:`px-5 py-2.5 text-base`};function i({variant:e=`primary`,size:t=`md`,children:i,className:a=``,disabled:o,style:s,...c}){return(0,n.jsx)(`button`,{className:`
        inline-flex items-center justify-center font-medium
        transition-all duration-150 ease-in-out
        cursor-pointer
        ${e===`ghost`?`hover:bg-elevated`:``}
        ${e===`primary`?`hover:brightness-110`:``}
        ${e===`danger`?`hover:brightness-110`:``}
        ${e===`secondary`?`hover:border-border-strong`:``}
        disabled:opacity-50 disabled:cursor-not-allowed
        ${r[t]}
        ${a}
      `.trim(),style:{borderRadius:`var(--radius-md)`,...{primary:{backgroundColor:`var(--accent-blue)`,color:`#ffffff`},secondary:{backgroundColor:`var(--bg-elevated)`,color:`var(--text-secondary)`,border:`1px solid var(--border)`},danger:{backgroundColor:`var(--accent-red)`,color:`#ffffff`},ghost:{backgroundColor:`transparent`,color:`var(--text-secondary)`}}[e],...s},disabled:o,...c,children:i})}export{i as t};