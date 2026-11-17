import type { Messages } from '../i18n/translations';

/** The rendered handwriting and the font coverage check share this corpus. */
export function handwritingCopy(messages: Messages) {
  return {
    recipeTitle: messages.lab.recipeTitle,
    craftAccent: messages.home.craftAccent,
    recipeName: messages.home.recipeName,
    recipeHandnote: messages.home.recipeHandnote,
    mediaHandnote: messages.home.mediaHandnote,
    multiply: '×',
  };
}
