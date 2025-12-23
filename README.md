# Copilot Chat Export

## Motivation
This Java program was created, because the [GitHub Copilot plugin for IntelliJ](https://plugins.jetbrains.com/plugin/17718-github-copilot--your-ai-pair-programmer) does not provide an export functionality (as of the time of writing).

## How it works
* It scans the configuration files stored by the GitHub Copilot plugin under `~/.config/github-copilot` and tries to extract the contents.

## Notes & Disclaimer
* I do not take any warranty for damages caused by using this program. I tested it successfully multiple times, though.
* It might stop working at any time with updates of the GitHub Copilot plugin.
* It was tested for the GitHub Copilot IntelliJ plugin on Mac. It wasn't tested on Windows. Feel free to adapt it to your needs.
* The exported files will be written in a folder `chat-export` under the current folder, where the program is running. It creates markdown files named `YYYY-MM-DD_<Title_of_chat>.md`.
* It will overwrite previously created files with the same name (this is intentional to be able to repeat the export multiple times), however if by chance there are two or more equally named chats from the same date found in the same run, they will receive a sequential number in the end and NOT be overwritten.
* It detects chats of mode "Ask" and "Agent". I think it does not support chats of mode "Edit" or "Plan".
* Empty chats are skipped.
* The user's chat message might not be 100% identical to the original due to some markdown reformatting.
* Chats of the currently open workspaces can not be exported, because those configrations are locked. There will be an error logged, which you can ignore.
* Older chats will probably not have the model name in the output.

## How to use
* Download the files of this Gist into a folder.
* Move the file CopilotChatExport.java to the subfolder src/main/java/com/example
* Move the file chat_template.th to the subfolder src/main/resources
* Compile and run with the IDE of your choice.
* Use a new workspace for running to be able to access all previous Copilot chats.